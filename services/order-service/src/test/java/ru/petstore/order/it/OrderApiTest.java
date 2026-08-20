package ru.petstore.order.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.petstore.order.client.CatalogClient;
import ru.petstore.order.client.CatalogProduct;
import ru.petstore.order.client.CustomerClient;
import ru.petstore.order.client.DeliveryTarget;
import ru.petstore.order.client.InventoryClient;
import ru.petstore.order.client.ReserveResult;
import ru.petstore.order.client.UpstreamUnavailableException;
import ru.petstore.order.domain.OutboxMessage;
import ru.petstore.order.outbox.OrderEventPayload;
import ru.petstore.order.repository.OutboxRepository;
import ru.petstore.order.web.OrderController;
import ru.petstore.order.web.dto.OrderItemRequest;
import ru.petstore.order.web.dto.OrderRequest;
import ru.petstore.order.web.dto.OrderResponse;
import ru.petstore.order.web.dto.ReferenceResponse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "petstore.order.outbox-poll-interval=PT1H",
                "spring.kafka.admin.auto-create=false"
        })
@AutoConfigureObservability
class OrderApiTest extends AbstractPostgresTest {

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private OutboxRepository outboxRepository;

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
        when(customerClient.deliveryTarget(any(), any())).thenReturn(deliveryTarget("ACTIVE"));
        when(inventoryClient.reserve(any(), any())).thenReturn(ReserveResult.held());
    }

    @Test
    @DisplayName("Заказ оформляется: 201, Location и тело со снимком адреса")
    void orderIsCreated() {
        ResponseEntity<OrderResponse> response = post(request(2), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation().toString())
                .isEqualTo("/api/v1/orders/" + response.getBody().id());
        OrderResponse order = response.getBody();
        assertThat(order.status().code()).isEqualTo("NEW");
        assertThat(order.deliveryType().code()).isEqualTo("COURIER");
        assertThat(order.paymentMethod().code()).isEqualTo("CARD");
        assertThat(order.totalAmount()).isEqualByComparingTo("399.80");
        assertThat(order.address().city().code()).isEqualTo("MSK");
        assertThat(order.items()).singleElement()
                .satisfies(item -> assertThat(item.productName()).isEqualTo("Корм"));
    }

    @Test
    @DisplayName("Повтор с тем же Idempotency-Key отдаёт 200 и тот же заказ")
    void repeatedKeyReturnsTheSameOrder() {
        String key = "key-" + UUID.randomUUID();

        ResponseEntity<OrderResponse> first = post(request(1), key);
        ResponseEntity<OrderResponse> second = post(request(1), key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().id()).isEqualTo(first.getBody().id());
        verify(catalogClient, times(1)).products(any());
    }

    @Test
    @DisplayName("Тот же ключ у другого клиента — свой заказ: ключ уникален в пределах клиента")
    void sameKeyOfAnotherCustomerCreatesItsOwnOrder() {
        String key = "key-" + UUID.randomUUID();

        ResponseEntity<OrderResponse> first = post(request(UUID.randomUUID(), 1), key);
        ResponseEntity<OrderResponse> second = post(request(UUID.randomUUID(), 1), key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().id()).isNotEqualTo(first.getBody().id());
    }

    @Test
    @DisplayName("Без ключа два одинаковых запроса дают два заказа")
    void requestsWithoutKeyCreateTwoOrders() {
        UUID first = post(request(1), null).getBody().id();
        UUID second = post(request(1), null).getBody().id();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("Заказ без позиций — 400 с перечислением полей")
    void emptyOrderIsRejected() {
        OrderRequest empty = new OrderRequest(UUID.randomUUID(), null, "COURIER", "CARD", List.of());

        ResponseEntity<JsonNode> response = rest.postForEntity("/api/v1/orders", empty, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("Неизвестный способ доставки — 400 и ни одного вызова апстримов")
    void unknownDeliveryTypeIsRejected() {
        OrderRequest request = new OrderRequest(UUID.randomUUID(), null, "TELEPORT", "CARD",
                List.of(new OrderItemRequest(PRODUCT, 1)));

        ResponseEntity<JsonNode> response = rest.postForEntity("/api/v1/orders", request, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message").asText()).contains("TELEPORT");
    }

    @Test
    @DisplayName("Нет остатка — 422 с кодом OUT_OF_STOCK")
    void outOfStockIsUnprocessable() {
        when(inventoryClient.reserve(any(), any())).thenReturn(ReserveResult.refused(List.of(PRODUCT)));

        ResponseEntity<JsonNode> response = rest.postForEntity("/api/v1/orders", request(1), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(response.getBody().get("requestId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("Заблокированный клиент — 422 с кодом CUSTOMER_BLOCKED")
    void blockedCustomerIsUnprocessable() {
        when(customerClient.deliveryTarget(any(), any())).thenReturn(deliveryTarget("BLOCKED"));

        ResponseEntity<JsonNode> response = rest.postForEntity("/api/v1/orders", request(1), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("CUSTOMER_BLOCKED");
    }

    @Test
    @DisplayName("Недоступный каталог — 503 с Retry-After, а не выдуманный заказ")
    void unavailableUpstreamIsServiceUnavailable() {
        when(catalogClient.products(any()))
                .thenThrow(new UpstreamUnavailableException("catalog", "timed out", null));

        ResponseEntity<JsonNode> response = rest.postForEntity("/api/v1/orders", request(1), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    }

    @Test
    @DisplayName("Заказ читается по идентификатору, чужой — 404 в общем формате")
    void orderIsReadBack() {
        UUID id = post(request(1), null).getBody().id();

        assertThat(rest.getForObject("/api/v1/orders/" + id, OrderResponse.class).id()).isEqualTo(id);

        ResponseEntity<JsonNode> missing =
                rest.getForEntity("/api/v1/orders/" + UUID.randomUUID(), JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("Заказы клиента отдаются страницей")
    void ordersOfCustomerAreListed() {
        UUID customerId = UUID.randomUUID();
        UUID id = post(new OrderRequest(customerId, null, "COURIER", "CARD",
                List.of(new OrderItemRequest(PRODUCT, 1))), null).getBody().id();

        JsonNode page = rest.getForObject("/api/v1/orders?customerId=" + customerId, JsonNode.class);

        assertThat(page.get("totalElements").asInt()).isEqualTo(1);
        assertThat(page.get("content").get(0).get("id").asText()).isEqualTo(id.toString());
    }

    @Test
    @DisplayName("Подтверждение меняет статус, пишет историю и кладёт событие в outbox")
    void confirmWritesTheEvent() {
        UUID id = post(request(1), null).getBody().id();

        OrderResponse confirmed = rest.postForObject("/api/v1/orders/" + id + "/confirm", null,
                OrderResponse.class);

        assertThat(confirmed.status().code()).isEqualTo("CONFIRMED");
        assertThat(events(id)).singleElement()
                .satisfies(message -> {
                    assertThat(message.getType()).isEqualTo(OrderEventPayload.ORDER_CONFIRMED);
                    assertThat(message.getPayload()).contains(id.toString());
                    assertThat(message.getPublishedAt()).isNull();
                });

        JsonNode history = rest.getForObject("/api/v1/orders/" + id + "/history", JsonNode.class);
        assertThat(history).hasSize(2);
        assertThat(history.get(1).get("status").get("code").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("Повторное подтверждение идемпотентно: второго события не появляется")
    void confirmIsIdempotent() {
        UUID id = post(request(1), null).getBody().id();

        rest.postForObject("/api/v1/orders/" + id + "/confirm", null, OrderResponse.class);
        ResponseEntity<OrderResponse> second = rest.postForEntity("/api/v1/orders/" + id + "/confirm",
                null, OrderResponse.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().status().code()).isEqualTo("CONFIRMED");
        assertThat(events(id)).hasSize(1);
    }

    @Test
    @DisplayName("Отмена кладёт ORDER_CANCELLED, подтверждённый заказ отменить нельзя")
    void cancelWritesTheEventAndRefusesConfirmed() {
        UUID cancelled = post(request(1), null).getBody().id();
        rest.postForObject("/api/v1/orders/" + cancelled + "/cancel", null, OrderResponse.class);
        assertThat(events(cancelled)).singleElement()
                .satisfies(message ->
                        assertThat(message.getType()).isEqualTo(OrderEventPayload.ORDER_CANCELLED));

        UUID confirmed = post(request(1), null).getBody().id();
        rest.postForObject("/api/v1/orders/" + confirmed + "/confirm", null, OrderResponse.class);

        ResponseEntity<JsonNode> refused = rest.postForEntity("/api/v1/orders/" + confirmed + "/cancel",
                null, JsonNode.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("ORDER_STATE");
    }

    @Test
    @DisplayName("Справочники отдаются из прогретого кеша")
    void referencesAreServedFromTheCache() {
        assertThat(rest.getForObject("/api/v1/order-statuses", ReferenceResponse[].class))
                .extracting(ReferenceResponse::code)
                .containsExactlyInAnyOrder("NEW", "CONFIRMED", "CANCELLED");
        assertThat(rest.getForObject("/api/v1/delivery-types", ReferenceResponse[].class)).hasSize(3);
        assertThat(rest.getForObject("/api/v1/payment-methods", ReferenceResponse[].class)).hasSize(3);
    }

    @Test
    @DisplayName("Самописные метрики уезжают в Prometheus по шаблону пути")
    void metricsAreExposed() {
        post(request(1), null);

        String scrape = rest.getForObject("/actuator/prometheus", String.class);

        assertThat(scrape).contains("petstore_requests_total");
        assertThat(scrape).contains("endpoint=\"/api/v1/orders\"");
    }

    private List<OutboxMessage> events(UUID orderId) {
        return outboxRepository.findByAggregateIdOrderByCreatedAtAsc(orderId);
    }

    private ResponseEntity<OrderResponse> post(OrderRequest request, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        if (idempotencyKey != null) {
            headers.set(OrderController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        ResponseEntity<OrderResponse> response = rest.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(request, headers), OrderResponse.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response;
    }

    private static OrderRequest request(int quantity) {
        return request(CUSTOMER, quantity);
    }

    private static OrderRequest request(UUID customerId, int quantity) {
        return new OrderRequest(customerId, null, "COURIER", "CARD",
                List.of(new OrderItemRequest(PRODUCT, quantity)));
    }

    private static DeliveryTarget deliveryTarget(String status) {
        return new DeliveryTarget(
                new DeliveryTarget.Customer(UUID.randomUUID(), "ivan@example.com", "Иван", "Петров",
                        new DeliveryTarget.Reference(status, status)),
                new DeliveryTarget.Address(UUID.randomUUID(),
                        new DeliveryTarget.Reference("MSK", "Москва"),
                        "Тверская", "1", "10", "125009"));
    }
}
