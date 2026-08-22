package ru.petstore.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GatewayPropertiesTest {

    @Test
    @DisplayName("Незаданный адрес сервиса — ошибка с именем свойства, а не маршрут в никуда")
    void missingServiceAddressIsRejected() {
        var properties = new GatewayProperties();

        assertThatThrownBy(() -> properties.service("catalog"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("petstore.gateway.services.catalog");
    }

    @Test
    @DisplayName("Заданный адрес отдаётся как есть")
    void configuredAddressIsReturned() {
        var properties = new GatewayProperties();
        properties.getServices().put("catalog", URI.create("http://catalog-service:8081"));

        assertThat(properties.service("catalog"))
                .isEqualTo(URI.create("http://catalog-service:8081"));
    }

    @Test
    @DisplayName("Нулевой предел частоты не проходит валидацию свойств")
    void zeroRateLimitFailsTheStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues("petstore.gateway.rate-limit.limit-for-period=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().isInstanceOf(BindValidationException.class));
    }

    @EnableConfigurationProperties(GatewayProperties.class)
    static class PropertiesConfiguration {
    }
}
