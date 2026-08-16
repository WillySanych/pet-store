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
| `subscription-service` | 8085            | `subscription` |

Общие модули: `common/common-core` (кеши, метрики, сквозное логирование, защита от перегрузки),
`common/common-proto` (контракты gRPC). Бенчмарки JMH — в `benchmarks/`.

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

Пять схем (`catalog`, `inventory`, `customer`, `orders`, `subscription`) создаются автоматически
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
