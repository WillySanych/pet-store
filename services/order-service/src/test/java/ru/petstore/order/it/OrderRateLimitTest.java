package ru.petstore.order.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.petstore.order.client.CatalogClient;
import ru.petstore.order.client.CatalogProduct;
import ru.petstore.order.client.CustomerClient;
import ru.petstore.order.client.DeliveryTarget;
import ru.petstore.order.client.InventoryClient;
import ru.petstore.order.client.ReserveResult;
import ru.petstore.order.web.dto.OrderItemRequest;
import ru.petstore.order.web.dto.OrderRequest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "petstore.order.outbox-poll-interval=PT1H",
        "spring.kafka.admin.auto-create=false",
        "resilience4j.ratelimiter.instances.orders.limit-for-period=1",
        "resilience4j.ratelimiter.instances.orders.limit-refresh-period=1m"
})
class OrderRateLimitTest extends AbstractPostgresTest {

    private static final UUID PRODUCT = UUID.randomUUID();

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private CatalogClient catalogClient;

    @MockitoBean
    private InventoryClient inventoryClient;

    @MockitoBean
    private CustomerClient customerClient;

    @BeforeEach
    void stubUpstreams() {
        when(catalogClient.products(any())).thenReturn(Map.of(PRODUCT,
                new CatalogProduct(PRODUCT, "Корм", new BigDecimal("199.90"), true)));
        when(customerClient.deliveryTarget(any(), any())).thenReturn(new DeliveryTarget(
                new DeliveryTarget.Customer(UUID.randomUUID(), "ivan@example.com", "Иван", "Петров",
                        new DeliveryTarget.Reference("ACTIVE", "Активный")),
                new DeliveryTarget.Address(UUID.randomUUID(),
                        new DeliveryTarget.Reference("MSK", "Москва"),
                        "Тверская", "1", "10", "125009")));
        when(inventoryClient.reserve(any(), any())).thenReturn(ReserveResult.held());
    }

    @Test
    @DisplayName("Превышение лимита создания заказов — 429 с Retry-After в общем формате")
    void burstIsRejectedWithTooManyRequests() {
        assertThat(post().getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> rejected = rest.postForEntity("/api/v1/orders", request(), JsonNode.class);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getBody().get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(rejected.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    }

    private ResponseEntity<JsonNode> post() {
        return rest.postForEntity("/api/v1/orders", request(), JsonNode.class);
    }

    private static OrderRequest request() {
        return new OrderRequest(UUID.randomUUID(), null, "COURIER", "CARD",
                List.of(new OrderItemRequest(PRODUCT, 1)));
    }
}
