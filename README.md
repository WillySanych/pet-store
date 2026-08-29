# pet-store

Учебный проект по курсу Java Advanced: онлайн-зоомагазин из пяти микросервисов на Java 21
и Spring Boot 3.5, который разворачивается в Kubernetes.

Что умеет: клиент через шлюз смотрит каталог товаров, создаёт в системе себя и адрес доставки,
оформляет заказ. Заказ резервирует остаток на складе, а после подтверждения склад списывает резерв
по событию из Kafka. Если в процессе отказал любой из сервисов, заказ не создаётся, а резерв
освобождается.

## Состав

| Приложение          | Порт            | Схема БД    | Что делает                             |
|---------------------|-----------------|-------------|----------------------------------------|
| `api-gateway`       | 8080            | —           | единая точка входа, сводный Swagger UI |
| `catalog-service`   | 8081, gRPC 9101 | `catalog`   | товары, категории, виды, бренды        |
| `inventory-service` | 8082, gRPC 9102 | `inventory` | остатки на складах и резервы           |
| `customer-service`  | 8083            | `customer`  | клиенты и адреса доставки              |
| `order-service`     | 8084            | `orders`    | оформление заказов                     |

Общие модули: `common/common-core` (кеши справочников, метрики, сквозное логирование, защита
от перегрузки, блокировка планировщиков) и `common/common-proto` (контракты gRPC). Бенчмарки
JMH — в `benchmarks/`, нагрузочные сценарии — в `load/jmeter/`.

База одна — `petstore`, у каждого сервиса в ней своя схема.

## Требования

Java 21, Maven 3.9+, Docker с Compose v2+. Для кластера — Kubernetes и Helm.

## Тесты

```bash
mvn clean test
```

Docker должен быть запущен: интеграционные тесты поднимают PostgreSQL и Kafka через
Testcontainers. Отдельно собирать проект перед запуском не нужно — образы собирают себя сами.

## Запуск локально

Два способа, оба поднимают весь стек целиком: docker compose и Helm в Kubernetes.

### Docker Compose

```bash
docker compose -f deploy/docker-compose.yml build     # собрать образы
LIQUIBASE_CONTEXTS=demo docker compose -f deploy/docker-compose.yml up -d   # запуск окружения с демо-данными
```

Поднимается девять контейнеров: четыре с инфраструктурой и пять приложений. Порты приложений —
как в таблице «Состав», инфраструктура доступна по этим адресам:

| Сервис     | Адрес                 | Доступ                                          |
|------------|-----------------------|-------------------------------------------------|
| PostgreSQL | `localhost:5432`      | база `petstore`, пользователь/пароль `petstore` |
| Kafka      | `localhost:9092`      | без аутентификации                              |
| Prometheus | http://localhost:9090 | —                                               |
| Grafana    | http://localhost:3000 | `admin` / `admin`                               |

Четыре схемы создаёт скрипт `deploy/postgres/init/01-schemas.sql` при первой инициализации тома.
Таблицы и справочные данные создаёт Liquibase при старте каждого сервиса.

Проверка живого сервиса — `http://localhost:<порт>/actuator/health`, метрики —
`/actuator/prometheus`. Весь API виден через шлюз: http://localhost:8080/swagger-ui.html.

Остановка:

```bash
docker compose -f deploy/docker-compose.yml down      # остановить и удалить контейнеры, данные останутся
docker compose -f deploy/docker-compose.yml down -v   # то же самое вместе с томами: база и Kafka станут пустыми
```

### Kubernetes

Образы берутся локальные, в реестр они не пушатся, поэтому перед установкой их нужно собрать
через `docker compose build`.

```bash
helm upgrade --install petstore deploy/helm/petstore -n petstore --create-namespace --wait --timeout 5m \
  --set catalog-service.env.LIQUIBASE_CONTEXTS=demo \
  --set inventory-service.env.LIQUIBASE_CONTEXTS=demo \
  --set customer-service.env.LIQUIBASE_CONTEXTS=demo

kubectl create configmap petstore-dashboards -n petstore --from-file=deploy/grafana/dashboards
```

Три `--set` включают демо-данные, по одному на сервис с тестовыми данными:
контекст `demo` в кластере задаётся каждому отдельно.

С `--wait` команда возвращается, только когда все поды релиза готовы, — на первом запуске это
минута-полторы, пока стартуют PostgreSQL, Kafka и сервисы. Прогресс при этом не печатается;
посмотреть его можно из соседнего окна: `kubectl get pods -n petstore -w`.

| Приложение       | Адрес                                  |
|------------------|----------------------------------------|
| Шлюз, Swagger UI | http://localhost:8080/swagger-ui.html  |
| Grafana          | http://localhost:3000, `admin`/`admin` |
| Prometheus       | http://localhost:9090                  |

У четырёх сервисов `ClusterIP`, до них — проброс порта:
`kubectl port-forward -n petstore svc/catalog-service 8081:8081`.

Удаление:

```bash
helm uninstall petstore -n petstore   # снести релиз, тома с данными останутся
kubectl delete namespace petstore     # удалить всё разом: релиз, дашборды и тома
```

### Демо-данные

По умолчанию база пустая: демо-данные лежат в Liquibase-контексте `demo`. Это 120 товаров
в каталоге, остатки на них на складах и 24 клиента с адресами — на них рассчитаны нагрузочные
сценарии.

## Карта тем лекций

Таблица покрытия тем:

| №  | Тема                                                     | Где в проекте                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
|----|----------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1  | Java 11 vs 17 vs 21, OpenJDK vs OracleJDK                | Java 21 во всех модулях — [`pom.xml`](pom.xml); образы на OpenJDK-сборке Eclipse Temurin — [`Dockerfile`](Dockerfile)                                                                                                                                                                                                                                                                                                                                                                                                                            |
| 2  | Memory management. JVM memory structure                  | `-Xmx` согласован с лимитами пода — [`values.yaml`](deploy/helm/petstore/charts/catalog-service/values.yaml); heap и паузы GC на дашборде [`application-internals.json`](deploy/grafana/dashboards/application-internals.json); как смотреть — [`docs/profiling.md`](docs/profiling.md)                                                                                                                                                                                                                                                          |
| 3  | Виртуальные потоки                                       | `spring.threads.virtual.enabled` — [`application.yml`](services/catalog-service/src/main/resources/application.yml); параллельные вызовы соседних сервисов — [`UpstreamExecutor`](services/order-service/src/main/java/ru/petstore/order/client/UpstreamExecutor.java)                                                                                                                                                                                                                                                                           |
| 4  | Memory dump                                              | как снять и как читать — [`docs/profiling.md`](docs/profiling.md)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| 5  | Java Microbenchmark Harness                              | модуль [`benchmarks/`](benchmarks/src/main/java/ru/petstore/benchmarks/ReferenceCacheReadBenchmark.java)                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| 6  | JMeter и организация нагрузочного тестирования           | четыре плана — [`load/jmeter/`](load/jmeter)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| 7  | j.u.c: Atomics, ConcurrentHashMap, ConcurrentSkipListMap | [`RefreshableReferenceCache`](common/common-core/src/main/java/ru/petstore/common/cache/RefreshableReferenceCache.java) — оба словаря и счётчики попаданий                                                                                                                                                                                                                                                                                                                                                                                       |
| 8  | j.u.c: Locks, ReadWriteLock, ReentrantLock               | `ReentrantReadWriteLock` при подмене справочника — [`RefreshableReferenceCache`](common/common-core/src/main/java/ru/petstore/common/cache/RefreshableReferenceCache.java)                                                                                                                                                                                                                                                                                                                                                                       |
| 9  | j.u.c: CountDownLatch, Semaphore, Phaser                 | `CountDownLatch` на прогреве кеша — [`RefreshableReferenceCache`](common/common-core/src/main/java/ru/petstore/common/cache/RefreshableReferenceCache.java); `Semaphore` в ограничителе частоты — [`SemaphoreRateLimiter`](services/api-gateway/src/main/java/ru/petstore/gateway/web/SemaphoreRateLimiter.java)                                                                                                                                                                                                                                 |
| 10 | Профилирование: Thread dump, JFR                         | [`docs/profiling.md`](docs/profiling.md)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| 11 | Профилирование: jvisualvm, asyncProfiler                 | VisualVM и флеймграф через JFR и JMC — [`docs/profiling.md`](docs/profiling.md), там же почему не async-profiler                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| 12 | Реактивное программирование: Reactor                     | шлюз на Spring Cloud Gateway (WebFlux) — [`api-gateway`](services/api-gateway/src/main/java/ru/petstore/gateway)                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| 13 | Java NIO                                                 | Netty под WebFlux в шлюзе — косвенно, через библиотеку                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| 14 | Вспоминаем Docker                                        | многоступенчатый [`Dockerfile`](Dockerfile) с `ARG SERVICE`, [`docker-compose.yml`](deploy/docker-compose.yml) на девять контейнеров                                                                                                                                                                                                                                                                                                                                                                                                             |
| 15 | Введение в Kubernetes                                    | Deployment, StatefulSet, Service, ConfigMap, Secret, PVC и три пробы — [`deployment.yaml`](deploy/helm/petstore/charts/catalog-service/templates/deployment.yaml)                                                                                                                                                                                                                                                                                                                                                                                |
| 16 | Обзор Helm                                               | umbrella-чарт и шесть подчартов — [`deploy/helm/petstore`](deploy/helm/petstore)                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| 17 | Разбор Actuator'а (Spring Boot 3)                        | пробы и группа readiness — [`CommonDefaultsEnvironmentPostProcessor`](common/common-core/src/main/java/ru/petstore/common/autoconfigure/CommonDefaultsEnvironmentPostProcessor.java), свой индикатор — [`CacheWarmupHealthIndicator`](common/common-core/src/main/java/ru/petstore/common/cache/CacheWarmupHealthIndicator.java)                                                                                                                                                                                                                 |
| 18 | Метрики                                                  | самописные метрики — [`ServiceMetrics`](common/common-core/src/main/java/ru/petstore/common/metrics/ServiceMetrics.java), снимает их [`RequestMetricsFilter`](common/common-core/src/main/java/ru/petstore/common/web/RequestMetricsFilter.java)                                                                                                                                                                                                                                                                                                 |
| 19 | Prometheus & Grafana                                     | [`prometheus.yml`](deploy/prometheus/prometheus.yml) для compose, [`prometheus.yml`](deploy/helm/petstore/charts/petstore-infra/files/prometheus.yml) для кластера, два дашборда — [`deploy/grafana/dashboards`](deploy/grafana/dashboards)                                                                                                                                                                                                                                                                                                      |
| 20 | Сквозное логирование в микросервисах                     | `X-Request-Id` и MDC — [`RequestTracingFilter`](common/common-core/src/main/java/ru/petstore/common/web/RequestTracingFilter.java), дальше в gRPC — [`RequestIdClientInterceptor`](common/common-core/src/main/java/ru/petstore/common/grpc/RequestIdClientInterceptor.java), в Kafka — [`RequestIdRecordInterceptor`](common/common-core/src/main/java/ru/petstore/common/kafka/RequestIdRecordInterceptor.java), в виртуальные потоки — [`MdcPropagation`](common/common-core/src/main/java/ru/petstore/common/concurrent/MdcPropagation.java) |
| 21 | Проектирование и архитектура микросервисов               | пять сервисов, три модели данных, у каждого сервиса с базой своя схема — [`services/`](services)                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| 22 | REST: Swagger, OpenAPI                                   | springdoc в каждом сервисе, описание API — [`@OpenAPIDefinition`](services/catalog-service/src/main/java/ru/petstore/catalog/CatalogServiceApplication.java), сводный UI в шлюзе — [`SwaggerUiConfig`](services/api-gateway/src/main/java/ru/petstore/gateway/config/SwaggerUiConfig.java)                                                                                                                                                                                                                                                       |
| 23 | Protobuf, gRPC                                           | контракты — [`common-proto`](common/common-proto/src/main/proto), серверы — [`CatalogGrpcService`](services/catalog-service/src/main/java/ru/petstore/catalog/grpc/CatalogGrpcService.java) и [`InventoryGrpcService`](services/inventory-service/src/main/java/ru/petstore/inventory/grpc/InventoryGrpcService.java), клиенты — [`order-service`](services/order-service/src/main/java/ru/petstore/order/client)                                                                                                                                |
| 24 | Kafka                                                    | топик `order-events` по схеме outbox: продюсер [`OutboxPublisher`](services/order-service/src/main/java/ru/petstore/order/outbox/OutboxPublisher.java), потребитель [`OrderEventListener`](services/inventory-service/src/main/java/ru/petstore/inventory/kafka/OrderEventListener.java)                                                                                                                                                                                                                                                         |
| 25 | Балансировка нагрузки                                    | маршруты шлюза — [`GatewayRoutesConfig`](services/api-gateway/src/main/java/ru/petstore/gateway/config/GatewayRoutesConfig.java); в кластере трафик по репликам раскидывает Service — [`service.yaml`](deploy/helm/petstore/charts/catalog-service/templates/service.yaml), число реплик задаёт `replicaCount` в [`values.yaml`](deploy/helm/petstore/charts/catalog-service/values.yaml)                                                                                                                                                        |
| 26 | Шаблоны проектирования отказоустойчивого сервиса         | повтор, circuit breaker и таймаут — [`UpstreamCall`](services/order-service/src/main/java/ru/petstore/order/client/UpstreamCall.java), защита от перегрузки — [`OverloadInterceptor`](common/common-core/src/main/java/ru/petstore/common/web/OverloadInterceptor.java) и [`SemaphoreRateLimiter`](services/api-gateway/src/main/java/ru/petstore/gateway/web/SemaphoreRateLimiter.java), надёжная доставка событий — [`OutboxPublisher`](services/order-service/src/main/java/ru/petstore/order/outbox/OutboxPublisher.java)                    |

