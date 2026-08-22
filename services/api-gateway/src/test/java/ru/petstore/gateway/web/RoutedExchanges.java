package ru.petstore.gateway.web;

import java.net.URI;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

final class RoutedExchanges {

    private static final URI UPSTREAM = URI.create("http://localhost:9999");

    private RoutedExchanges() {
    }

    static MockServerWebExchange routed(MockServerHttpRequest.BaseBuilder<?> request, String routeId) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, Route.async()
                .id(routeId)
                .uri(UPSTREAM)
                .predicate(ignored -> true)
                .build());
        return exchange;
    }
}
