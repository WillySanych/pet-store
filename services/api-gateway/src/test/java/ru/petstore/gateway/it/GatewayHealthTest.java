package ru.petstore.gateway.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoint.health.cache.time-to-live=0")
class GatewayHealthTest extends AbstractGatewayTest {

    @Autowired
    private WebTestClient client;

    @Test
    @DisplayName("Здоровье шлюза сводит статусы всех четырёх сервисов")
    void healthAggregatesEveryService() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.downstream.components.catalog.status").isEqualTo("UP")
                .jsonPath("$.components.downstream.components.inventory.status").isEqualTo("UP")
                .jsonPath("$.components.downstream.components.customer.status").isEqualTo("UP")
                .jsonPath("$.components.downstream.components.order.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("Упавший сервис виден в сводке, но пробы шлюза остаются зелёными")
    void downstreamOutageIsVisibleButDoesNotAffectProbes() {
        upstream("catalog").answerWith(503, "{\"status\":\"DOWN\"}");

        client.get().uri("/actuator/health").exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.status").isEqualTo("DOWN")
                .jsonPath("$.components.downstream.components.catalog.status").isEqualTo("DOWN")
                .jsonPath("$.components.downstream.components.order.status").isEqualTo("UP");

        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    }
}
