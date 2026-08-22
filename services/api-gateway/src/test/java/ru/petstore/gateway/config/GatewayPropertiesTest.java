package ru.petstore.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GatewayPropertiesTest {

    @Test
    @DisplayName("Незаданный адрес сервиса — ошибка с именем свойства, а не маршрут в никуда")
    void missingServiceAddressIsRejected() {
        var properties = new GatewayProperties();

        assertThatThrownBy(() -> properties.service(GatewayRoutesConfig.CATALOG))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("petstore.gateway.services.catalog");
    }

    @Test
    @DisplayName("Заданный адрес отдаётся как есть")
    void configuredAddressIsReturned() {
        var properties = new GatewayProperties();
        properties.getServices().put(GatewayRoutesConfig.CATALOG, URI.create("http://catalog-service:8081"));

        assertThat(properties.service(GatewayRoutesConfig.CATALOG))
                .isEqualTo(URI.create("http://catalog-service:8081"));
    }
}
