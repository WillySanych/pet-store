package ru.petstore.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.petstore.gateway.web.RoutedExchanges.routed;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import ru.petstore.gateway.metrics.GatewayMetrics;

class RequestMetricsFilterTest {

    private static final WebFilterChain EMPTY_CHAIN = exchange -> Mono.empty();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final RequestMetricsFilter filter =
            new RequestMetricsFilter(new GatewayMetrics(registry), "/actuator");

    @Test
    @DisplayName("Запрос считается под меткой маршрута, а не URI")
    void requestIsCountedUnderTheRouteId() {
        filter.filter(routed(MockServerHttpRequest.get("/api/v1/products/42"), "catalog"), EMPTY_CHAIN).block();

        assertThat(registry.get(GatewayMetrics.REQUESTS)
                .tag("endpoint", "catalog")
                .tag("status", "200")
                .tag("outcome", "success")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get(GatewayMetrics.REQUEST_DURATION)
                .tag("endpoint", "catalog")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Не совпавший ни с одним маршрутом запрос учитывается отдельно")
    void unroutedRequestIsCountedSeparately() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/nothing/here"));
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);

        filter.filter(exchange, EMPTY_CHAIN).block();

        assertThat(registry.get(GatewayMetrics.REQUESTS)
                .tag("endpoint", RequestMetricsFilter.UNROUTED)
                .tag("status", "404")
                .tag("outcome", "failure")
                .counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Упавший запрос учитывается как 500")
    void failedRequestIsCountedAsServerError() {
        WebFilterChain failing = ignored -> Mono.error(new IllegalStateException("upstream is gone"));

        filter.filter(routed(MockServerHttpRequest.get("/api/v1/products"), "catalog"), failing)
                .onErrorResume(error -> Mono.empty())
                .block();

        assertThat(registry.get(GatewayMetrics.REQUESTS)
                .tag("endpoint", "catalog")
                .tag("status", "500")
                .tag("outcome", "failure")
                .counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Обращения к actuator не попадают в rps приложения")
    void actuatorTrafficIsNotCounted() {
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/prometheus")),
                EMPTY_CHAIN).block();

        assertThat(registry.find(GatewayMetrics.REQUESTS).counters()).isEmpty();
    }

    @Test
    @DisplayName("Префикс исключения берётся из настройки")
    void excludePrefixComesFromConfiguration() {
        var filter = new RequestMetricsFilter(new GatewayMetrics(registry), "/manage");

        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/manage/prometheus")),
                EMPTY_CHAIN).block();
        filter.filter(routed(MockServerHttpRequest.get("/actuator/prometheus"), "catalog"),
                EMPTY_CHAIN).block();

        assertThat(registry.get(GatewayMetrics.REQUESTS)
                .tag("endpoint", "catalog")
                .counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Пустой префикс исключения — ошибка, а не молча выключенные метрики")
    void blankExcludePrefixIsRejected() {
        assertThatThrownBy(() -> new RequestMetricsFilter(new GatewayMetrics(registry), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("petstore.metrics.exclude-prefix");
    }
}
