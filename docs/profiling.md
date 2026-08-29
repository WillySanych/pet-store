# Профилирование

Снятие с сервиса запись JFR, дамп потоков и дамп памяти - и что в них смотреть.

## Подготовка

Стенд поднимается так же, как описано в [README](../README.md#kubernetes): сборка образов
`docker compose build`, затем `helm upgrade --install` с контекстом `demo`. Демо-данные здесь
обязательны - на них рассчитаны планы JMeter, которыми даётся нагрузка.

Четыре прикладных сервиса наружу не смотрят - у них `ClusterIP`; на localhost Docker Desktop
публикует только `LoadBalancer`-сервисы: шлюз (8080), Grafana (3000) и Prometheus (9090).
Прямому прогону jmeter тестов нужны туннели: `catalog-read.jmx` бьёт в 8081, `order-create.jmx` - в 8082
и 8084. `customer-service` пробрасывать не надо, к нему ходит только `order-service` внутри
кластера.

Туннели:

```bash
# HTTP - для нагрузки
kubectl port-forward -n petstore svc/catalog-service   8081:8081 &
kubectl port-forward -n petstore svc/inventory-service 8082:8082 &
kubectl port-forward -n petstore svc/order-service     8084:8084 &

# JMX - для VisualVM и JMC
kubectl port-forward -n petstore deployment/api-gateway       9080:9010 &
kubectl port-forward -n petstore deployment/catalog-service   9081:9010 &
kubectl port-forward -n petstore deployment/inventory-service 9082:9010 &
kubectl port-forward -n petstore deployment/customer-service  9083:9010 &
kubectl port-forward -n petstore deployment/order-service     9084:9010 &

kill $(jobs -p)     # когда закончили
```

Нагрузку даёт JMeter. В GUI план открывается как есть:

```bash
jmeter -t load/jmeter/catalog-read.jmx
```

Чтение (`catalog-read.jmx`) даёт ровный профиль, запись (`order-create.jmx`) - всю сагу
с базой, gRPC и Kafka. Для первого знакомства лучше чтение, для интересных находок - запись.

## Подключаемся из UI

В чартах у всех пяти сервисов включён JMX на порту `9010` - через него VisualVM и JMC видят
процесс в поде как обычную JVM. Туннели подняты выше, подключаемся на локальный порт сервиса:

| Сервис            | Адрес для UI     |
|-------------------|------------------|
| api-gateway       | `localhost:9080` |
| catalog-service   | `localhost:9081` |
| inventory-service | `localhost:9082` |
| customer-service  | `localhost:9083` |
| order-service     | `localhost:9084` |

**VisualVM**: File → Add JMX Connection → адрес из таблицы → Connect.
Появятся живые графики CPU, heap, классов и потоков, вкладка Sampler и кнопки Thread Dump и Heap Dump.

**JDK Mission Control**: File → Connect → Create new connection → host `localhost`, port из таблицы.
Кроме мониторинга там Flight Recorder - записи JFR запускаются и открываются прямо в нём, с Flame View.

## Что снимаем из UI

**Запись JFR.** В JMC: правый клик по подключению → Start Flight Recording, профиль `Profiling`
(это тот же `settings=profile`), длительность - на время прогона. Запись останется в дереве
слева, открывается двойным щелчком.

**Дамп потоков.** В VisualVM вкладка Threads → Thread Dump. Снимать имеет смысл два-три раза
с интервалом в несколько секунд: одиночный снимок покажет случайный момент, а по серии видно,
что поток стоит на одном и том же месте. Искать: потоки в `BLOCKED`, ожидание соединения
из пула HikariCP, потоки-носители виртуальных (`ForkJoinPool-*`).

**Дамп памяти.** В VisualVM вкладка Monitor → Heap Dump. Файл останется внутри пода,
в `/tmp`; VisualVM откроет его сам, если подключён по JMX, иначе забираем через `kubectl cp`
и открываем как файл. Ожидаемые крупные жильцы - записи справочных кешей и сущности Hibernate
текущих транзакций; всё остальное в верхушке гистограммы - повод искать утечку.

Про дамп памяти есть оговорка: он останавливает JVM. `livenessProbe` в чартах - период 10 с
и порог 3, то есть под перезапустят примерно через 30 секунд молчания. Дамп heap в 512 МБ
укладывается в несколько секунд, запаса хватает, но при большем `-Xmx` об этом стоит помнить.

## Что смотреть в записи JFR

JMC показывает то же самое вкладками, но список полезного одинаков:

- **Hot Methods** - где сгорает процессор, и Flame View к нему.
- **Pinned Threads** - виртуальные потоки, прибитые к несущему.
- **GC Pauses** - паузы сборщика.
- **Allocation** - кто больше всех аллоцирует.
- **Java Thread Park** - ожидание на замках `java.util.concurrent`, с классом замка.

Одна тонкость, которая экономит время: вкладка Lock Instances и вид «contention» показывают
только мониторы `synchronized`. Замки из `java.util.concurrent` - например
`ReentrantReadWriteLock` в наших кешах - поток не блокируют, а паркуют, и туда не попадают.
Их видно в событиях `jdk.ThreadPark` (колонка «Class Parked On»).

## Что смотреть именно в этом проекте

- **Виртуальные потоки.** Pinned Threads: поток, вошедший в `synchronized`, прикалывается
  к несущему. Свой код этого избегает, но библиотеки могут - это первое, что проверяют,
  если rps упёрся в потолок при незагруженном процессоре.
- **Пул соединений.** Виртуальных потоков много, соединений в HikariCP мало. В дампе потоков
  это выглядит как очередь на `getConnection`.
- **Кеши справочников.** События `jdk.ThreadPark` покажут ожидание на `ReentrantReadWriteLock` -
  ту самую блокировку на чтении, что нашёл JMH-бенчмарк.
- **Публикация outbox.** `OutboxPublisher` работает под ShedLock на одной реплике,
  поэтому под `order-create.jmx` он может стать узким местом раньше самих сервисов.
