package ru.petstore.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SemaphoreRateLimiterTest {

    @Test
    @DisplayName("В пределах окна запросы проходят, сверх лимита — отклоняются")
    void requestsPassWithinTheWindow() {
        var limiter = new SemaphoreRateLimiter(2);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();

        assertThat(limiter.tryAcquire()).isFalse();
        assertThat(limiter.availablePermits()).isZero();
    }

    @Test
    @DisplayName("Пополнение открывает следующее окно")
    void refillOpensTheNextWindow() {
        var limiter = new SemaphoreRateLimiter(1);
        limiter.tryAcquire();

        limiter.refill();

        assertThat(limiter.availablePermits()).isEqualTo(1);
        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("Разрешения не накапливаются между окнами")
    void permitsDoNotAccumulate() {
        var limiter = new SemaphoreRateLimiter(3);

        limiter.refill();
        limiter.refill();

        assertThat(limiter.availablePermits()).isEqualTo(3);
    }

    @Test
    @DisplayName("Под конкурентной нагрузкой проходит ровно лимит")
    void concurrentBurstIsCappedAtTheLimit() throws Exception {
        int limit = 10;
        int threads = 200;
        var limiter = new SemaphoreRateLimiter(limit);
        var passed = new AtomicInteger();
        var start = new CountDownLatch(1);
        var finished = new CountDownLatch(threads);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        if (limiter.tryAcquire()) {
                            passed.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(passed.get()).isEqualTo(limit);
    }
}
