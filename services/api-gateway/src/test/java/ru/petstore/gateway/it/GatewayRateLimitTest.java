package ru.petstore.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import ru.petstore.gateway.web.SemaphoreRateLimiter;

@TestPropertySource(properties = {
        "petstore.gateway.rate-limit.limit-for-period=1",
        "petstore.gateway.rate-limit.refresh-period=PT1H"
})
@AutoConfigureObservability
class GatewayRateLimitTest extends AbstractGatewayTest {

    @Autowired
    private SemaphoreRateLimiter limiter;

    @BeforeEach
    void openWindow() {
        limiter.refill();
    }

    @Test
    @DisplayName("Сверх лимита шлюз отвечает 429 и не идёт в сервис")
    void burstIsRejectedAtTheGateway() {
        client.get().uri("/api/v1/products").exchange().expectStatus().isOk();

        client.get().uri("/api/v1/products").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.code").isEqualTo("RATE_LIMITED")
                .jsonPath("$.requestId").isNotEmpty();

        assertThat(upstream("catalog").requestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Исчерпанное окно не мешает пробам и сбору метрик")
    void actuatorSurvivesTheOverload() {
        client.get().uri("/api/v1/products").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/products").exchange().expectStatus().isEqualTo(429);

        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        client.get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
    }
}
