package ru.petstore.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import ru.petstore.common.web.ReferenceResponse;
import ru.petstore.inventory.service.ReservationService;
import ru.petstore.inventory.service.ReserveLine;
import ru.petstore.inventory.web.dto.StockRequest;
import ru.petstore.inventory.web.dto.StockResponse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.liquibase.contexts=test",
        "grpc.server.port=0",
        "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureObservability
class InventoryApiTest extends AbstractPostgresTest {

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private ReservationService reservationService;

    private StockResponse put(UUID productId, StockRequest request) {
        var response = testRestTemplate.exchange("/api/v1/stock/" + productId, HttpMethod.PUT,
                new HttpEntity<>(request), StockResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @Test
    @DisplayName("Установленный остаток доступен по идентификатору товара")
    void stockIsCreatedAndReadBack() {
        UUID product = UUID.randomUUID();

        var created = put(product, new StockRequest("MSK", 40));

        assertThat(created.quantity()).isEqualTo(40);
        assertThat(created.reserved()).isZero();
        assertThat(created.available()).isEqualTo(40);
        assertThat(created.warehouse().code()).isEqualTo("MSK");

        var found = testRestTemplate.getForObject("/api/v1/stock/" + product, StockResponse.class);
        assertThat(found.productId()).isEqualTo(product);
        assertThat(found.quantity()).isEqualTo(40);
    }

    @Test
    @DisplayName("Повторный PUT меняет остаток, а не заводит вторую строку")
    void repeatedPutUpdatesTheSameRow() {
        UUID product = UUID.randomUUID();
        put(product, new StockRequest("MSK", 40));

        var updated = put(product, new StockRequest("SPB", 15));

        assertThat(updated.quantity()).isEqualTo(15);
        assertThat(updated.warehouse().code()).isEqualTo("SPB");
        assertThat(testRestTemplate.getForObject("/api/v1/stock/" + product, StockResponse.class).quantity())
                .isEqualTo(15);
    }

    @Test
    @DisplayName("Остаток отражает удержанное резервом количество")
    void reservedAmountIsVisibleInTheResponse() {
        UUID product = UUID.randomUUID();
        put(product, new StockRequest("MSK", 10));
        reservationService.reserve(UUID.randomUUID(), List.of(new ReserveLine(product, 3)));

        var found = testRestTemplate.getForObject("/api/v1/stock/" + product, StockResponse.class);

        assertThat(found.quantity()).isEqualTo(10);
        assertThat(found.reserved()).isEqualTo(3);
        assertThat(found.available()).isEqualTo(7);
    }

    @Test
    @DisplayName("Товар без остатка — 404 в общем формате")
    void missingStockReturns404() {
        var response = testRestTemplate.getForEntity("/api/v1/stock/" + UUID.randomUUID(), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().get("requestId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("Неизвестный склад — 400, а не строка остатка в никуда")
    void unknownWarehouseReturns400() {
        var response = testRestTemplate.exchange("/api/v1/stock/" + UUID.randomUUID(), HttpMethod.PUT,
                new HttpEntity<>(new StockRequest("NOWHERE", 5)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().get("message").asText()).contains("NOWHERE");
    }

    @Test
    @DisplayName("Отрицательный остаток — 400 с перечислением полей")
    void negativeQuantityReturns400() {
        var response = testRestTemplate.exchange("/api/v1/stock/" + UUID.randomUUID(), HttpMethod.PUT,
                new HttpEntity<>(new StockRequest("MSK", -1)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().get("message").asText()).contains("quantity");
    }

    @Test
    @DisplayName("Нечитаемый идентификатор товара — 400, а не 500")
    void malformedProductIdReturns400() {
        var response = testRestTemplate.getForEntity("/api/v1/stock/not-a-uuid", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Склады отдаются из кеша отсортированными по коду")
    void warehousesAreServedFromCache() {
        var warehouses = testRestTemplate.exchange("/api/v1/warehouses", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<ReferenceResponse>>() {
                }).getBody();

        assertThat(warehouses).extracting(ReferenceResponse::code)
                .containsExactly("EKB", "MSK", "SPB");
    }

    @Test
    @DisplayName("Статусы резервов отдаются справочником")
    void reservationStatusesAreServedFromCache() {
        var statuses = testRestTemplate.exchange("/api/v1/reservation-statuses", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<ReferenceResponse>>() {
                }).getBody();

        assertThat(statuses).extracting(ReferenceResponse::code)
                .containsExactly("ACTIVE", "COMMITTED", "EXPIRED", "RELEASED");
    }

    @Test
    @DisplayName("Справочник не отдаёт наружу внутренний идентификатор")
    void referenceTablesDoNotExposeInternalIds() {
        var warehouses = testRestTemplate.getForObject("/api/v1/warehouses", JsonNode.class);

        assertThat(warehouses.get(0).has("id")).isFalse();
        assertThat(warehouses.get(0).get("code").asText()).isNotBlank();
    }

    @Test
    @DisplayName("Readiness поднимается только с прогретыми кешами")
    void readinessReportsWarmedUpCaches() {
        var response = testRestTemplate.getForEntity("/actuator/health/readiness", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName("Размеры кешей уезжают в Prometheus")
    void cacheMetricsAreExported() {
        testRestTemplate.getForObject("/api/v1/warehouses", String.class);

        var metrics = testRestTemplate.getForObject("/actuator/prometheus", String.class);

        assertThat(metrics)
                .contains("petstore_cache_size")
                .contains("cache=\"warehouses\"")
                .contains("cache=\"reservation-statuses\"");
    }
}
