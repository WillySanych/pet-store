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

Java 21, Maven 3.9+, Docker с Compose v2+, Kubernetes, Docker и Helm.

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

Двенадцать демо-товаров лежат под контекстом `demo` и по умолчанию **не** добавляются —
скрипт на их добавление находится в отдельном контексте: `LIQUIBASE_CONTEXTS=demo`.

### `inventory-service`

```bash
mvn -pl services/inventory-service spring-boot:run
```

Остатки и резервы: REST на 8082, gRPC-сервер `Reserve`/`Release` на 9102, потребитель Kafka
топика `order-events`. Таблицы схемы `inventory` и справочники (`warehouse`, `reservation_status`)
заводит Liquibase при старте, поэтому инфраструктура из `docker-compose` должна быть поднята.

Демо-остатки на те же двенадцать товаров каталога — тоже под контекстом `demo`:
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

Три демо-клиента с адресами — под контекстом `demo`: `LIQUIBASE_CONTEXTS=demo`.

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

## Метрики и дашборды

Каждый сервис отдаёт метрики на `/actuator/prometheus`. Prometheus из `docker-compose` собирает их
статическими таргетами `host.docker.internal:8080`…`8084`, то есть с процессов, запущенных
на хосте через `spring-boot:run` или `java -jar`. Живы ли все пять целей, видно
на http://localhost:9090/targets.

Grafana поднимается уже настроенной: `deploy/grafana/provisioning/` заводит источник данных
и папку `PetStore`, дашборды берутся из `deploy/grafana/dashboards/`.

| Дашборд | Что показывает |
|---|---|
| **Service Overview** — http://localhost:3000/d/petstore-overview | запросы в секунду, доля неуспешных ответов, перцентили p50/p95/p99, коды ответов, разбивка по экземплярам, таблица самых нагруженных эндпоинтов |
| **Application Internals** — http://localhost:3000/d/petstore-internals | размеры справочных кешей и доля попаданий, ошибки по типам, повторы к апстримам и состояние circuit breaker, отказы по перегрузке, heap, паузы GC и потоки |

Сверху у обоих переключатель источника данных и мультиселект сервисов.

Панели «Повторы и отказы апстримов» и «Отказы по перегрузке» пусты, пока событие не случилось
хотя бы раз: Micrometer заводит счётчик на первом инкременте, до этого метрики нет в выдаче
`/actuator/prometheus`. Это не поломка дашборда.

В Kubernetes используется второй конфиг — `deploy/prometheus/prometheus-k8s.yml`: адреса подов
известны только service discovery, поэтому таргеты берутся из `kubernetes_sd_configs`, а поды
отбираются по аннотациям `prometheus.io/scrape`, `prometheus.io/path` и `prometheus.io/port`.
Relabeling кладёт имя пода в `instance`, поэтому панели с разбивкой по экземплярам одинаково
работают и в compose, и в кластере.
