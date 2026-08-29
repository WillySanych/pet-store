package ru.petstore.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

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
}
