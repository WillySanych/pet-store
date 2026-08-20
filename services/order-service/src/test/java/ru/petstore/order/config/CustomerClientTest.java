package ru.petstore.order.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.common.web.RequestTracingFilter;
import ru.petstore.order.client.CustomerClient;
import ru.petstore.order.client.DeliveryTarget;
import ru.petstore.order.client.UpstreamExecutor;
import ru.petstore.order.client.UpstreamCall;
import ru.petstore.order.client.UpstreamFailedException;
import ru.petstore.order.client.UpstreamNotFoundException;
import ru.petstore.order.client.UpstreamUnavailableException;

/**
 * Lives next to {@link UpstreamConfig}: the test binds its mock server to the same builder the
 * bean is made of, so the base URL and the tracing header are the production ones.
 */
class CustomerClientTest {

    private static final UUID CUSTOMER = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    private static final UUID ADDRESS = UUID.fromString("a0000000-0000-0000-0000-000000000001");

    private static final String BODY = """
            {
              "customer": {
                "id": "b0000000-0000-0000-0000-000000000001",
                "email": "ivan@example.com",
                "firstName": "Иван",
                "lastName": "Петров",
                "status": {"code": "ACTIVE", "name": "Активный"},
                "createdAt": "2026-08-01T10:00:00Z"
              },
              "address": {
                "id": "a0000000-0000-0000-0000-000000000001",
                "city": {"code": "MSK", "name": "Москва"},
                "street": "Тверская",
                "building": "1",
                "apartment": "10",
                "postalCode": "125009",
                "defaultAddress": true
              }
            }""";

    private final UpstreamExecutor executor = new UpstreamExecutor();

    private MockRestServiceServer server;
    private CustomerClient client;

    @BeforeEach
    void setUp() {
        OrderProperties properties = new OrderProperties();
        properties.setCustomerServiceUrl("http://customer-service:8083");
        RestClient.Builder builder = UpstreamConfig.customerRestClientBuilder(properties);
        server = MockRestServiceServer.bindTo(builder).build();

        UpstreamCall call = new UpstreamCall("customer",
                Retry.of("customer", RetryConfig.custom().maxAttempts(1).build()),
                CircuitBreaker.ofDefaults("customer"),
                TimeLimiter.of("customer", TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(5)).build()),
                executor,
                new ServiceMetrics(new SimpleMeterRegistry()));
        client = new CustomerClient(builder.build(), call);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        executor.close();
    }

    @Test
    @DisplayName("Клиент и адрес доставки читаются одним запросом")
    void deliveryTargetIsRead() {
        server.expect(requestTo("http://customer-service:8083/api/v1/customers/" + CUSTOMER
                        + "/delivery-target"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        DeliveryTarget target = client.deliveryTarget(CUSTOMER, null);

        assertThat(target.customer().email()).isEqualTo("ivan@example.com");
        assertThat(target.customer().status().code()).isEqualTo("ACTIVE");
        assertThat(target.address().city().code()).isEqualTo("MSK");
        assertThat(target.address().street()).isEqualTo("Тверская");
        server.verify();
    }

    @Test
    @DisplayName("Явный адрес уезжает параметром запроса")
    void addressIdIsPassedAsQueryParameter() {
        server.expect(requestTo("http://customer-service:8083/api/v1/customers/" + CUSTOMER
                        + "/delivery-target?addressId=" + ADDRESS))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        client.deliveryTarget(CUSTOMER, ADDRESS);

        server.verify();
    }

    @Test
    @DisplayName("Сквозной идентификатор запроса уезжает заголовком")
    void requestIdTravelsInTheHeader() {
        MDC.put(RequestTracingFilter.MDC_KEY, "trace-1");
        server.expect(header(RequestTracingFilter.REQUEST_ID_HEADER, "trace-1"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        client.deliveryTarget(CUSTOMER, null);

        server.verify();
    }

    @Test
    @DisplayName("404 — не найден клиент или адрес, а не поломка апстрима")
    void notFoundIsItsOwnFailure() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("delivery-target")))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> client.deliveryTarget(CUSTOMER, null))
                .isInstanceOf(UpstreamNotFoundException.class)
                .hasMessageContaining(CUSTOMER.toString());
    }

    @Test
    @DisplayName("5xx — повторяемая недоступность")
    void serverErrorIsRetryable() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("delivery-target")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.deliveryTarget(CUSTOMER, null))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    @DisplayName("4xx кроме 404 — ошибка вызова, повтор не поможет")
    void clientErrorIsNotRetryable() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("delivery-target")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.deliveryTarget(CUSTOMER, null))
                .isInstanceOf(UpstreamFailedException.class);
    }
}
