package ru.petstore.order.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.order.config.OrderProperties;
import ru.petstore.proto.catalog.CatalogServiceGrpc;
import ru.petstore.proto.catalog.GetProductsRequest;
import ru.petstore.proto.catalog.GetProductsResponse;
import ru.petstore.proto.catalog.Product;

class CatalogClientTest {

    private static final UUID FOOD = UUID.randomUUID();
    private static final UUID TOY = UUID.randomUUID();

    private final FakeCatalog catalog = new FakeCatalog();
    private final UpstreamExecutor executor = new UpstreamExecutor();

    private Server server;
    private ManagedChannel channel;
    private CatalogClient client;

    @BeforeEach
    void setUp() throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(catalog).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        UpstreamCall call = new UpstreamCall("catalog",
                Retry.of("catalog", RetryConfig.custom().maxAttempts(1).build()),
                CircuitBreaker.ofDefaults("catalog"),
                TimeLimiter.of("catalog", TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(5)).build()),
                executor,
                new ServiceMetrics(new SimpleMeterRegistry()));
        client = new CatalogClient(CatalogServiceGrpc.newBlockingStub(channel), call, new OrderProperties());
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
        executor.close();
    }

    @Test
    @DisplayName("Товары приходят с ценой и признаком активности")
    void productsAreMapped() {
        catalog.answer = GetProductsResponse.newBuilder()
                .addProducts(product(FOOD, "Корм", "199.90", true))
                .addProducts(product(TOY, "Мячик", "50.00", false))
                .build();

        Map<UUID, CatalogProduct> products = client.products(List.of(FOOD, TOY));

        assertThat(products).hasSize(2);
        assertThat(products.get(FOOD).name()).isEqualTo("Корм");
        assertThat(products.get(FOOD).price()).isEqualByComparingTo(new BigDecimal("199.90"));
        assertThat(products.get(FOOD).active()).isTrue();
        assertThat(products.get(TOY).active()).isFalse();
    }

    @Test
    @DisplayName("Пустой список не превращается в вызов")
    void emptyRequestIsNotSent() {
        assertThat(client.products(List.of())).isEmpty();
        assertThat(catalog.calls).hasValue(0);
    }

    @Test
    @DisplayName("Дубликаты идентификаторов уезжают в каталог один раз")
    void duplicateIdsAreSentOnce() {
        catalog.answer = GetProductsResponse.newBuilder()
                .addProducts(product(FOOD, "Корм", "199.90", true))
                .build();

        client.products(List.of(FOOD, FOOD));

        assertThat(catalog.lastRequest.getProductIdsList()).containsExactly(FOOD.toString());
    }

    @Test
    @DisplayName("Пропавший в ответе товар просто отсутствует в карте: сверка идёт по идентификатору")
    void missingProductIsAbsentFromTheMap() {
        catalog.answer = GetProductsResponse.newBuilder()
                .addProducts(product(FOOD, "Корм", "199.90", true))
                .build();

        assertThat(client.products(List.of(FOOD, TOY))).containsOnlyKeys(FOOD);
    }

    @Test
    @DisplayName("UNAVAILABLE — повторяемая ошибка")
    void unavailableBecomesRetryable() {
        catalog.error = Status.UNAVAILABLE;

        assertThatThrownBy(() -> client.products(List.of(FOOD)))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    @DisplayName("INTERNAL — не повторяемая: каталог сломан, а не занят")
    void internalBecomesFailure() {
        catalog.error = Status.INTERNAL;

        assertThatThrownBy(() -> client.products(List.of(FOOD)))
                .isInstanceOf(UpstreamFailedException.class);
    }

    private static Product product(UUID id, String name, String price, boolean active) {
        return Product.newBuilder()
                .setId(id.toString())
                .setName(name)
                .setPrice(price)
                .setActive(active)
                .build();
    }

    private static final class FakeCatalog extends CatalogServiceGrpc.CatalogServiceImplBase {

        private final AtomicInteger calls = new AtomicInteger();
        private GetProductsResponse answer = GetProductsResponse.getDefaultInstance();
        private GetProductsRequest lastRequest;
        private Status error;

        @Override
        public void getProducts(GetProductsRequest request, StreamObserver<GetProductsResponse> observer) {
            calls.incrementAndGet();
            lastRequest = request;
            if (error != null) {
                observer.onError(error.asRuntimeException());
                return;
            }
            observer.onNext(answer);
            observer.onCompleted();
        }
    }
}
