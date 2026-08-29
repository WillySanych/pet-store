# pet-store

Отказоустойчивая микросервисная архитектура онлайн-зоомагазина с развёртыванием в Kubernetes.

## Состав

| Приложение             | Порт            | Схема БД       |
|------------------------|-----------------|----------------|
| `api-gateway`          | 8080            | —              |
| `catalog-service`      | 8081, gRPC 9101 | `catalog`      |
| `inventory-service`    | 8082, gRPC 9102 | `inventory`    |
| `customer-service`     | 8083            | `customer`     |
| `order-service`        | 8084            | `orders`       |

Общие модули: `common/common-core` (кеши, справочники, метрики, сквозное логирование, защита
от перегрузки, блокировка планировщиков), `common/common-proto` (контракты gRPC).
Бенчмарки JMH — в `benchmarks/`.

## Требования

Java 21, Maven 3.9+, Docker с Compose v2+; для развёртывания в кластере — Kubernetes
(в проекте это Kubernetes от Docker Desktop) и Helm (чарты проверены на 4.2).

## Сборка

```bash
mvn clean install
```

## Локальная инфраструктура

PostgreSQL, Kafka, Prometheus и Grafana поднимаются одной командой:

```bash
docker compose -f deploy/docker-compose.yml up -d
```

| Сервис     | Адрес                 | Доступ                                          |
|------------|-----------------------|-------------------------------------------------|
| PostgreSQL | `localhost:5432`      | база `petstore`, пользователь/пароль `petstore` |
| Kafka      | `localhost:9092`      | без аутентификации                              |
| Prometheus | http://localhost:9090 | —                                               |
| Grafana    | http://localhost:3000 | `admin` / `admin`                               |

Четыре схемы (`catalog`, `inventory`, `customer`, `orders`) создаются автоматически
при первой инициализации тома скриптом `deploy/postgres/init/01-schemas.sql`.

Остановить с сохранением данных — `docker compose -f deploy/docker-compose.yml stop`.
Удалить вместе с томами — `... down -v`.

## Запуск сервиса

```bash
mvn -pl services/catalog-service spring-boot:run
```

Проверка: http://localhost:8081/actuator/health и http://localhost:8081/actuator/prometheus

### `catalog-service`

Сама схема `catalog` появляется при первой инициализации тома PostgreSQL — её создаёт
`deploy/postgres/init/01-schemas.sql` вместе с четырьмя остальными. Таблицы и справочники
(`category`, `species`, `brand`) заводит в ней Liquibase при старте сервиса, поэтому
инфраструктура из `docker-compose` должна быть поднята.

Демо-каталог лежит под контекстом `demo` и по умолчанию **не** добавляется: двенадцать
именованных товаров плюс однотипный хвост до ста двадцати позиций, по которому листают
страницы нагрузочные сценарии. Включается контекстом: `LIQUIBASE_CONTEXTS=demo`.

### `inventory-service`

```bash
mvn -pl services/inventory-service spring-boot:run
```

Остатки и резервы: REST на 8082, gRPC-сервер `Reserve`/`Release` на 9102, потребитель Kafka
топика `order-events`. Таблицы схемы `inventory` и справочники (`warehouse`, `reservation_status`)
заводит Liquibase при старте, поэтому инфраструктура из `docker-compose` должна быть поднята.

Демо-остатки на все сто двадцать товаров каталога — тоже под контекстом `demo`:
`LIQUIBASE_CONTEXTS=demo` в обоих сервисах даёт каталог, который можно заказать.

Остаток списывается **только** по событию `ORDER_CONFIRMED` из Kafka. Топик `order-events` заводит
его продюсер, `order-service`.

### `customer-service`

```bash
mvn -pl services/customer-service spring-boot:run
```

Клиенты и их адреса доставки: REST на 8083. Таблицы схемы `customer` и справочники
(`city`, `customer_status`) заводит Liquibase при старте, поэтому инфраструктура
из `docker-compose` должна быть поднята.

Адреса — подресурс клиента: `/api/v1/customers/{id}/addresses`.
У клиента с адресами ровно один основной: первый адрес становится им автоматически, назначение
нового снимает признак с прежнего, а удаление основного передаёт его старейшему из оставшихся.
Снять признак напрямую нельзя — его переводят на другой адрес.

`order-service` берёт клиента и адрес доставки одним запросом:
`GET /api/v1/customers/{id}/delivery-target`; параметр `addressId` выбирает адрес вместо
основного. Заблокированному клиенту сервис не отказывает — статус едет в ответе, решение
за вызывающим.

Двадцать четыре демо-клиента с адресами — под контекстом `demo`: `LIQUIBASE_CONTEXTS=demo`.

### `order-service`

```bash
mvn -pl services/order-service spring-boot:run
```

Оформление заказов: REST на 8084, gRPC-клиент каталога и склада, продюсер Kafka `order-events`.
Таблицы схемы `orders` и справочники (`order_status`, `delivery_type`, `payment_method`) заводит
Liquibase при старте.

Демо-заказов нет: заказ создаётся через API на демо-данных каталога, склада и клиентов.

### `api-gateway`

```bash
mvn -pl services/api-gateway spring-boot:run
```

Единая точка входа: 8080, маршруты на четыре сервиса, сквозной `X-Request-Id`, ограничение
частоты запросов и сводный Swagger UI. Базы данных у шлюза нет.

Адреса сервисов задаются переменными окружения `CATALOG_URL`, `INVENTORY_URL`, `CUSTOMER_URL`
и `ORDER_URL` (по умолчанию `http://localhost:8081`…`8084`), предел частоты —
`GATEWAY_RATE_LIMIT` (по умолчанию 500 запросов в секунду на экземпляр).

## Запуск в Docker

Образ у всех пяти сервисов собирается одним многоступенчатым `Dockerfile` в корне репозитория:
имя модуля передаётся аргументом `SERVICE`, первая ступень (`maven:3.9.11-eclipse-temurin-21`)
собирает jar командой `mvn -pl services/${SERVICE} -am -DskipTests package`, вторая
(`eclipse-temurin:21-jdk`) получает только jar и запускает его. Тесты в образе
пропускаются намеренно: интеграционным нужен Testcontainers, то есть Docker внутри сборки, —
они проходят раньше, в `mvn install`.

Команда для сборки:

```bash
docker compose -f deploy/docker-compose.yml build
```

Инфраструктура вместе с сервисами:

```bash
docker compose -f deploy/docker-compose.yml up -d
```

Демо-данные и JVM-флаги передаются переменными окружения самой команды:

```bash
LIQUIBASE_CONTEXTS=demo docker compose -f deploy/docker-compose.yml up -d
JAVA_OPTS="-Xmx512m" docker compose -f deploy/docker-compose.yml up -d
```

## Запуск в Kubernetes

Umbrella-чарт `deploy/helm/petstore` держит шесть подчартов в `deploy/helm/petstore/charts/`:
`petstore-infra` (PostgreSQL, Kafka, Prometheus, Grafana) и по чарту на каждый сервис.

```bash
kubectl create namespace petstore
kubectl create configmap petstore-dashboards -n petstore --from-file=deploy/grafana/dashboards
helm upgrade --install petstore deploy/helm/petstore -n petstore
kubectl get pods -n petstore -w
```

ConfigMap с дашбордами создаётся отдельной командой, потому что единственная их копия лежит
в `deploy/grafana/dashboards/` — за пределами чарта, а Helm читает файлы только из своего
каталога. Без неё Grafana всё равно поднимется, только без дашбордов.

| Куда смотреть    | Как добраться                                                                                |
|------------------|----------------------------------------------------------------------------------------------|
| Шлюз, Swagger UI | http://localhost:8080/swagger-ui.html — Docker Desktop публикует `LoadBalancer` на localhost |
| Grafana          | `kubectl port-forward -n petstore svc/grafana 3000:3000`                                     |
| Prometheus       | `kubectl port-forward -n petstore svc/prometheus 9090:9090`                                  |

Наружу смотрит только шлюз — у него `LoadBalancer`, у четырёх сервисов `ClusterIP`.
До сервиса напрямую — `kubectl port-forward -n petstore svc/catalog-service 8081:8081`.

Реплики добавляются на ходу:

```bash
kubectl scale deployment/catalog-service -n petstore --replicas=3
```

Демо-данные — `LIQUIBASE_CONTEXTS` в values всех четырёх сервисов с базой:

```bash
helm upgrade --install petstore deploy/helm/petstore -n petstore \
  --set catalog-service.env.LIQUIBASE_CONTEXTS=demo \
  --set inventory-service.env.LIQUIBASE_CONTEXTS=demo \
  --set customer-service.env.LIQUIBASE_CONTEXTS=demo
```

Снести релиз: `helm uninstall petstore -n petstore`. Тома PostgreSQL и Kafka переживают удаление
релиза — `kubectl delete pvc -n petstore --all`, если нужна чистая база.

## Метрики и дашборды

Каждый сервис отдаёт метрики на `/actuator/prometheus`. Prometheus из `docker-compose` собирает их
статическими таргетами `host.docker.internal:8080`…`8084`. Адрес один на оба режима: он ведёт
на хост, а туда published-портами выходят и контейнеры сервисов, и процессы, запущенные
через `spring-boot:run`. Живы ли все пять целей, видно на http://localhost:9090/targets.

Grafana поднимается уже настроенной: `deploy/grafana/provisioning/` заводит источник данных
и папку `PetStore`, дашборды берутся из `deploy/grafana/dashboards/`.

| Дашборд                                                                | Что показывает                                                                                                                                             |
|------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Service Overview** — http://localhost:3000/d/petstore-overview       | запросы в секунду, доля неуспешных ответов, перцентили p50/p95/p99, коды ответов, разбивка по экземплярам, таблица самых нагруженных эндпоинтов            |
| **Application Internals** — http://localhost:3000/d/petstore-internals | размеры справочных кешей и доля попаданий, ошибки по типам, повторы к апстримам и состояние circuit breaker, отказы по перегрузке, heap, паузы GC и потоки |

Сверху у обоих переключатель источника данных и мультиселект сервисов.

Панели «Повторы и отказы апстримов» и «Отказы по перегрузке» пусты, пока событие не случилось
хотя бы раз: Micrometer заводит счётчик на первом инкременте, до этого метрики нет в выдаче
`/actuator/prometheus`. Это не поломка дашборда.

В Kubernetes работает второй конфиг — он нужен только кластеру, поэтому и лежит в чарте:
`deploy/helm/petstore/charts/petstore-infra/files/prometheus.yml`. Адреса подов известны только
service discovery, поэтому таргеты берутся из `kubernetes_sd_configs`, а поды отбираются
по аннотациям `prometheus.io/scrape`, `prometheus.io/path` и `prometheus.io/port`.
Relabeling кладёт имя пода в `instance`, поэтому панели с разбивкой по экземплярам одинаково
работают и в compose, и в кластере.

## Нагрузочные сценарии (JMeter)

`load/jmeter/` — четыре плана под Apache JMeter 5.6.x. Каждый открывается в GUI (`jmeter -t <файл>`)
и запускается из консоли; все параметры задаются ключами `-J`, править файл ради потоков
или длительности не нужно.

| План                           | Что делает                                                                                                                                |
|--------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| `functional-all-endpoints.jmx` | по запросу на каждый из 33 REST-эндпоинтов четырёх сервисов через шлюз плюс проба самого шлюза                                            |
| `catalog-read.jmx`             | чтение каталога напрямую в `catalog-service`, мимо шлюза: список с пагинацией, товар по id, фильтр по справочнику, чтение прогретого кеша |
| `order-create.jmx`             | запись напрямую в `inventory-service` и `order-service`: оформление и подтверждение заказа — сага целиком, вместе с outbox и Kafka        |
| `overload.jmx`                 | перегрузка: bulkhead сервиса и rate limiter шлюза                                                                                         |

Перед прогоном поднимаются инфраструктура и пять сервисов
(`docker compose -f deploy/docker-compose.yml up -d` либо Helm).

```bash
# функциональный прогон — 37 запросов, всё должно быть зелёным
jmeter -n -t load/jmeter/functional-all-endpoints.jmx -l target/functional.jtl

# чтение и запись — мимо шлюза, прямо в сервисы; -e -o собирает HTML-отчёт рядом с .jtl
jmeter -n -t load/jmeter/catalog-read.jmx -l target/read.jtl -e -o target/read-report \
       -JcatalogPort=8081 -Jthreads=50 -Jrampup=15 -Jduration=180
jmeter -n -t load/jmeter/order-create.jmx -l target/order.jtl \
       -JinventoryPort=8082 -JorderPort=8084 \
       -Jthreads=20 -Jrampup=10 -Jduration=180 -JstockQuantity=200000

# перегрузка: 200 потоков мимо шлюза в catalog-service и 150 через шлюз
jmeter -n -t load/jmeter/overload.jmx -l target/overload.jtl \
       -JbulkheadThreads=200 -JgatewayThreads=150 -Jrampup=5 -Jduration=120
```

Чтение и запись бьют прямо в сервисы, мимо шлюза, — это и есть значения по умолчанию
(`catalogPort=8081`, `inventoryPort=8082`, `orderPort=8084`). Через шлюз потолок задавал бы
не сервис: `RateLimitFilter` — глобальный фильтр на 500 запросов в секунду
(`GATEWAY_RATE_LIMIT`), и 50 потоков по прогретому кешу выбирают его разрешения за первую же
секунду. Ограничитель частоты есть только в шлюзе; у сервисов свой bulkhead из `common-core`,
но он считает одновременные запросы (64 с ожиданием 50 мс) — ни 50, ни 20 потоков его
не задевают. Прогон через шлюз ставится теми же ключами (`-JcatalogPort=8080`, либо
`-JinventoryPort=8080 -JorderPort=8080`), но меряет уже шлюз; специально его ограничитель
показывает `overload.jmx`.

В Kubernetes сервисы наружу не смотрят (`ClusterIP`, снаружи только шлюз), поэтому прямому
прогону нужен проброс портов: `kubectl port-forward -n petstore svc/catalog-service 8081:8081`
и так же для 8082 и 8084.

Три нагрузочных плана работают по демо-данным — товары и клиенты с фиксированными
идентификаторами, — поэтому сервисы должны быть запущены с `LIQUIBASE_CONTEXTS=demo`:
`catalog-read.jmx` листает пять страниц по двадцать товаров, `order-create.jmx` заказывает
демо-товары от имени демо-клиентов, `overload.jmx` просит страницу на сто позиций.
`functional-all-endpoints.jmx` демо-данных не требует: он заводит свой товар, своего клиента
и свой адрес, а в конце удаляет клиента (адреса уносит каскад базы) и снимает товар с продажи.
Неактивный товар, его остаток и два заказа остаются — удалять их API не умеет.

`order-create.jmx` перед основной группой поднимает остатки шести демо-товаров
до `-JstockQuantity`, каждый на своём складе из демо-данных: иначе длинный прогон упрётся
в пустой склад, а не в производительность.

`overload.jmx` бьёт двумя группами по разным адресам — одна напрямую в `catalog-service` (8081),
вторая в шлюз (8080). Через один шлюз обе проверки не поставить: его лимит в 500 запросов
в секунду отбил бы трафик раньше, чем тот дошёл бы до bulkhead сервиса. Ответ `429` здесь штатный,
поэтому ассерт принимает `200` и `429` с «игнорировать статус» — без этого JMeter засчитал бы
каждый отказ ограничителя в ошибки и отчёт стал бы нечитаемым. Разбивка по кодам смотрится
в `.jtl` или на дашборде Grafana:

```bash
awk -F, 'NR>1 {print $4}' target/overload.jtl | sort | uniq -c
```

Растёт `petstore_overload_rejected_total` — счётчик общий у сервисного bulkhead и у ограничителя
шлюза, различаются они меткой `service`; у шлюза дополнительно падает gauge
`petstore_ratelimit_available`. Latency успешных запросов при этом остаётся стабильной —
это и есть проверяемое поведение.
