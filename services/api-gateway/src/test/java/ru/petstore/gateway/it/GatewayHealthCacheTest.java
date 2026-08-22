package ru.petstore.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GatewayHealthCacheTest extends AbstractGatewayTest {

    @Test
    @DisplayName("Здоровье кешируется: запрос к шлюзу не превращается в четыре запроса к сервисам")
    void healthIsCachedAndDoesNotAmplifyRequests() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        int afterFirst = upstream("catalog").requestCount();

        client.get().uri("/actuator/health").exchange().expectStatus().isOk();

        assertThat(afterFirst).isEqualTo(1);
        assertThat(upstream("catalog").requestCount()).isEqualTo(1);
    }
}
