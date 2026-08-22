package ru.petstore.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import ru.petstore.gateway.web.RequestTracingFilter;

class GatewayRoutingTest extends AbstractGatewayTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/api/v1/products,               catalog",
            "/api/v1/products/42,            catalog",
            "/api/v1/categories,             catalog",
            "/api/v1/species,                catalog",
            "/api/v1/brands,                 catalog",
            "/api/v1/stock/42,               inventory",
            "/api/v1/warehouses,             inventory",
            "/api/v1/reservation-statuses,   inventory",
            "/api/v1/customers,              customer",
            "/api/v1/customers/42/addresses, customer",
            "/api/v1/cities,                 customer",
            "/api/v1/customer-statuses,      customer",
            "/api/v1/orders,                 order",
            "/api/v1/orders/42/history,      order",
            "/api/v1/order-statuses,         order",
            "/api/v1/delivery-types,         order",
            "/api/v1/payment-methods,        order"
    })
    @DisplayName("Каждый путь уходит в свой сервис нетронутым")
    void pathIsRoutedToItsService(String path, String service) {
        client.get().uri(path).exchange().expectStatus().isOk();

        assertThat(upstream(service).awaitRequest().path()).isEqualTo(path);
        UPSTREAMS.forEach((name, stub) -> {
            if (!name.equals(service)) {
                assertThat(stub.requestCount()).as("лишний запрос в %s", name).isZero();
            }
        });
    }

    @Test
    @DisplayName("Метод и тело запроса доходят до сервиса")
    void methodAndBodyAreProxied() {
        client.post().uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"customerId\":\"42\"}")
                .exchange()
                .expectStatus().isOk();

        assertThat(upstream("order").awaitRequest().method()).isEqualTo("POST");
    }

    @Test
    @DisplayName("Шлюз выдаёт X-Request-Id, отдаёт его клиенту и пробрасывает в сервис")
    void requestIdIsIssuedAndPassedDownstream() {
        String issued = client.get().uri("/api/v1/products").exchange()
                .expectStatus().isOk()
                .expectHeader().exists(RequestTracingFilter.REQUEST_ID_HEADER)
                .returnResult(String.class)
                .getResponseHeaders()
                .getFirst(RequestTracingFilter.REQUEST_ID_HEADER);

        assertThat(upstream("catalog").awaitRequest().requestId()).isEqualTo(issued);
    }

    @Test
    @DisplayName("Входящий X-Request-Id не перезаписывается")
    void incomingRequestIdIsPreserved() {
        String incoming = UUID.randomUUID().toString();

        client.get().uri("/api/v1/products")
                .header(RequestTracingFilter.REQUEST_ID_HEADER, incoming)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(RequestTracingFilter.REQUEST_ID_HEADER, incoming);

        assertThat(upstream("catalog").awaitRequest().requestId()).isEqualTo(incoming);
    }

    @Test
    @DisplayName("Идентификатор возвращается клиенту ровно один раз")
    void requestIdIsReturnedOnce() {
        String incoming = UUID.randomUUID().toString();

        var headers = client.get().uri("/api/v1/products")
                .header(RequestTracingFilter.REQUEST_ID_HEADER, incoming)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseHeaders();

        assertThat(headers.get(RequestTracingFilter.REQUEST_ID_HEADER)).containsExactly(incoming);
    }

    @Test
    @DisplayName("Неизвестный путь не уходит ни в один сервис")
    void unknownPathIsNotRouted() {
        client.get().uri("/api/v1/unknown").exchange().expectStatus().isNotFound();

        UPSTREAMS.forEach((name, stub) ->
                assertThat(stub.requestCount()).as("запрос в %s", name).isZero());
    }

    @Test
    @DisplayName("Спецификация сервиса отдаётся через шлюз для сводного Swagger UI")
    void apiDocsOfAServiceAreProxied() {
        client.get().uri("/api-docs/catalog").exchange().expectStatus().isOk();

        assertThat(upstream("catalog").awaitRequest().path()).isEqualTo("/v3/api-docs");
    }

    @Test
    @DisplayName("Сводный Swagger UI перечисляет спецификации всех сервисов")
    void swaggerUiListsEveryService() {
        client.get().uri("/v3/api-docs/swagger-config").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.urls[*].url").value(hasItems(
                        "/api-docs/catalog", "/api-docs/inventory",
                        "/api-docs/customer", "/api-docs/order"));
    }
}
