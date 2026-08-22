package ru.petstore.gateway.it;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import ru.petstore.gateway.ApiGatewayApplication;

class GatewayStartupTest {

    @Test
    @DisplayName("Нулевой предел частоты не даёт шлюзу подняться")
    void zeroRateLimitFailsTheStartup() {
        var application = new SpringApplication(ApiGatewayApplication.class);

        assertThatThrownBy(() -> application.run(
                "--server.port=0",
                "--petstore.gateway.rate-limit.limit-for-period=0"))
                .rootCause()
                .isInstanceOf(BindValidationException.class);
    }
}
