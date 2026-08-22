package ru.petstore.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import ru.petstore.gateway.metrics.GatewayMetrics;

class RequestMetricsFilterTest {

    private static final WebFilterChain EMPTY_CHAIN = exchange -> Mono.empty();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final RequestMetricsFilter filter = new RequestMetricsFilter(new GatewayMetrics(registry));

    private static ServerWebExchange routedExchange(String path, String routeId) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, Route.async()
                .id(routeId)
                .uri(URI.create("http://localhost:8081"))
                .predicate(ignored -> true)
                .build());
        return exchange;
    }

    @Test
    @DisplayName("Запрос считается под меткой маршрута, а не URI")
    void requestIsCountedUnderTheRouteId() {
        filter.filter(routedExchange("/api/v1/products/42", "catalog"), EMPTY_CHAIN).block();

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

        filter.filter(routedExchange("/api/v1/products", "catalog"), failing)
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
}
