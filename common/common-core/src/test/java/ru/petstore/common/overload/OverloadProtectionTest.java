package ru.petstore.common.overload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.petstore.common.metrics.ServiceMetrics;

@ExtendWith(MockitoExtension.class)
class OverloadProtectionTest {

    @Mock
    private ServiceMetrics metrics;

    @Test
    @DisplayName("В пределах лимита запрос выполняется")
    void requestRunsWithinLimit() {
        var protection = new OverloadProtection(2, metrics);

        String result = protection.call("/api/v1/products", () -> "ok");

        assertThat(result).isEqualTo("ok");
        verify(metrics, never()).recordOverloadRejected("/api/v1/products");
    }

    @Test
    @DisplayName("Разрешение возвращается даже при исключении")
    void permitIsReleasedEvenOnException() {
        var protection = new OverloadProtection(1, metrics);

        assertThatThrownBy(() -> protection.call("/api/v1/products", () -> {
            throw new IllegalStateException("failure inside the handler");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(protection.availablePermits()).isEqualTo(1);
        assertThat(protection.call("/api/v1/products", () -> "ok")).isEqualTo("ok");
    }

    @Test
    @DisplayName("Сверх лимита запрос отклоняется и считается в метрике")
    void requestOverLimitIsRejectedAndCounted() throws Exception {
        var protection = new OverloadProtection(1, metrics);
        var occupied = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            pool.submit(() -> protection.call("/api/v1/orders", () -> {
                occupied.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "busy";
            }));

            assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> protection.call("/api/v1/orders", () -> "second"))
                    .isInstanceOf(OverloadedException.class)
                    .hasMessageContaining("/api/v1/orders");

            verify(metrics).recordOverloadRejected("/api/v1/orders");
            release.countDown();
        }
    }
}
