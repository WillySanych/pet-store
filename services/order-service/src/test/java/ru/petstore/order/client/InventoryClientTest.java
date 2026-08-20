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
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.order.config.OrderProperties;
import ru.petstore.proto.inventory.InventoryServiceGrpc;
import ru.petstore.proto.inventory.ReleaseRequest;
import ru.petstore.proto.inventory.ReleaseResponse;
import ru.petstore.proto.inventory.ReserveRequest;
import ru.petstore.proto.inventory.ReserveResponse;

class InventoryClientTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    private final FakeInventory inventory = new FakeInventory();
    private final UpstreamExecutor executor = new UpstreamExecutor();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private Server server;
    private ManagedChannel channel;
    private InventoryClient client;

    @BeforeEach
    void setUp() throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(inventory).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        ServiceMetrics metrics = new ServiceMetrics(registry);
        UpstreamCall call = new UpstreamCall("inventory",
                Retry.of("inventory", RetryConfig.custom().maxAttempts(1).build()),
                CircuitBreaker.ofDefaults("inventory"),
                TimeLimiter.of("inventory", TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(5)).build()),
                executor, metrics);
        client = new InventoryClient(InventoryServiceGrpc.newBlockingStub(channel), call, metrics,
                new OrderProperties());
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
        executor.close();
    }

    @Test
    @DisplayName("Удержанный остаток отдаётся как reserved")
    void heldStockIsReported() {
        inventory.reserveAnswer = ReserveResponse.newBuilder().setReserved(true).build();

        ReserveResult result = client.reserve(ORDER, Map.of(PRODUCT, 3));

        assertThat(result.reserved()).isTrue();
        assertThat(inventory.lastReserve.getOrderId()).isEqualTo(ORDER.toString());
        assertThat(inventory.lastReserve.getItems(0).getQuantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("Нехватка приходит списком недоступных товаров")
    void shortageIsReportedWithProductIds() {
        inventory.reserveAnswer = ReserveResponse.newBuilder()
                .setReserved(false)
                .addUnavailableProductIds(PRODUCT.toString())
                .build();

        ReserveResult result = client.reserve(ORDER, Map.of(PRODUCT, 3));

        assertThat(result.reserved()).isFalse();
        assertThat(result.unavailableProductIds()).containsExactly(PRODUCT);
    }

    @Test
    @DisplayName("ABORTED — проигранная гонка, её повторяют")
    void abortedIsRetryable() {
        inventory.error = Status.ABORTED;

        assertThatThrownBy(() -> client.reserve(ORDER, Map.of(PRODUCT, 1)))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    @DisplayName("FAILED_PRECONDITION — состояние резерва, повтор не поможет")
    void failedPreconditionIsNotRetryable() {
        inventory.error = Status.FAILED_PRECONDITION;

        assertThatThrownBy(() -> client.reserve(ORDER, Map.of(PRODUCT, 1)))
                .isInstanceOf(UpstreamFailedException.class);
    }

    @Test
    @DisplayName("Освобождение резерва доходит до склада")
    void releaseReachesInventory() {
        assertThat(client.release(ORDER)).isTrue();
        assertThat(inventory.lastRelease.getOrderId()).isEqualTo(ORDER.toString());
    }

    @Test
    @DisplayName("Компенсация не бросает исключение: ошибка уходит в метрику и лог")
    void quietReleaseSwallowsFailure() {
        inventory.error = Status.INTERNAL;

        client.releaseQuietly(ORDER);

        assertThat(registry.get(ServiceMetrics.ERRORS).tag("type", "compensation_failed")
                .counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Отказ склада освободить резерв тоже считается")
    void refusedReleaseIsCounted() {
        inventory.releaseAnswer = ReleaseResponse.newBuilder().setReleased(false).build();

        client.releaseQuietly(ORDER);

        assertThat(registry.get(ServiceMetrics.ERRORS).tag("type", "compensation_refused")
                .counter().count()).isEqualTo(1);
    }

    private static final class FakeInventory extends InventoryServiceGrpc.InventoryServiceImplBase {

        private ReserveResponse reserveAnswer = ReserveResponse.newBuilder().setReserved(true).build();
        private ReleaseResponse releaseAnswer = ReleaseResponse.newBuilder().setReleased(true).build();
        private ReserveRequest lastReserve;
        private ReleaseRequest lastRelease;
        private Status error;

        @Override
        public void reserve(ReserveRequest request, StreamObserver<ReserveResponse> observer) {
            lastReserve = request;
            if (error != null) {
                observer.onError(error.asRuntimeException());
                return;
            }
            observer.onNext(reserveAnswer);
            observer.onCompleted();
        }

        @Override
        public void release(ReleaseRequest request, StreamObserver<ReleaseResponse> observer) {
            lastRelease = request;
            if (error != null) {
                observer.onError(error.asRuntimeException());
                return;
            }
            observer.onNext(releaseAnswer);
            observer.onCompleted();
        }
    }
}
