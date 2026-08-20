package ru.petstore.order.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.petstore.common.metrics.ServiceMetrics;

class UpstreamCallTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ServiceMetrics metrics = new ServiceMetrics(registry);
    private final UpstreamExecutor executor = new UpstreamExecutor();

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    @DisplayName("Недоступный апстрим повторяется до успеха, повторы считаются метрикой")
    void unavailableCallIsRetried() {
        AtomicInteger attempts = new AtomicInteger();
        UpstreamCall call = call(retry(3), breaker(100), timeLimiter(Duration.ofSeconds(2)));

        String result = call.call(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new UpstreamUnavailableException("catalog", "down", null);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
        assertThat(registry.get(ServiceMetrics.UPSTREAM_RETRIES).tag("upstream", "catalog")
                .counter().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Отказ по делу не повторяется: товара нет и на второй попытке")
    void businessFailureIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        UpstreamCall call = call(retry(3), breaker(100), timeLimiter(Duration.ofSeconds(2)));

        assertThatThrownBy(() -> call.<String>call(() -> {
            attempts.incrementAndGet();
            throw new UpstreamFailedException("catalog", "INVALID_ARGUMENT", null);
        })).isInstanceOf(UpstreamFailedException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    @DisplayName("Зависший вызов обрывается таймаутом на каждой попытке")
    void hangingCallTimesOut() {
        UpstreamCall call = call(retry(1), breaker(100), timeLimiter(Duration.ofMillis(100)));

        assertThatThrownBy(() -> call.<String>call(() -> {
            try {
                Thread.sleep(Duration.ofSeconds(1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "too late";
        })).isInstanceOf(UpstreamUnavailableException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    @DisplayName("Разомкнутая цепь отвечает сразу и до апстрима не доходит")
    void openCircuitFailsFast() {
        AtomicInteger attempts = new AtomicInteger();
        UpstreamCall call = call(retry(1), breaker(1), timeLimiter(Duration.ofSeconds(2)));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> call.<String>call(() -> {
                attempts.incrementAndGet();
                throw new UpstreamUnavailableException("catalog", "down", null);
            })).isInstanceOf(UpstreamUnavailableException.class);
        }

        assertThat(attempts).hasValue(1);
    }

    private UpstreamCall call(Retry retry, CircuitBreaker breaker, TimeLimiter timeLimiter) {
        return new UpstreamCall("catalog", retry, breaker, timeLimiter, executor, metrics);
    }

    private static Retry retry(int attempts) {
        return Retry.of("catalog", RetryConfig.custom()
                .maxAttempts(attempts)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(UpstreamUnavailableException.class)
                .build());
    }

    private static CircuitBreaker breaker(int minimumCalls) {
        return CircuitBreaker.of("catalog", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(minimumCalls)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordExceptions(UpstreamUnavailableException.class)
                .build());
    }

    private static TimeLimiter timeLimiter(Duration timeout) {
        return TimeLimiter.of("catalog", TimeLimiterConfig.custom()
                .timeoutDuration(timeout)
                .cancelRunningFuture(true)
                .build());
    }
}
