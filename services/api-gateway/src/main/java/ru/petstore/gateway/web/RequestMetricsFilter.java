package ru.petstore.gateway.web;

import java.time.Duration;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import ru.petstore.gateway.metrics.GatewayMetrics;

public class RequestMetricsFilter implements WebFilter, Ordered {

    /** Requests that matched no route: a 404 of the gateway or its own endpoint. */
    static final String UNROUTED = "unrouted";

    /** Nginx code for a request the client dropped before an answer: not a status the gateway can send. */
    static final int CLIENT_CLOSED_REQUEST = 499;

    private final GatewayMetrics metrics;

    private final String excludePrefix;

    public RequestMetricsFilter(GatewayMetrics metrics, String excludePrefix) {
        if (!StringUtils.hasText(excludePrefix)) {
            throw new IllegalArgumentException("petstore.metrics.exclude-prefix must not be blank");
        }
        this.metrics = metrics;
        this.excludePrefix = excludePrefix;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getPath().value().startsWith(excludePrefix)) {
            return chain.filter(exchange);
        }
        long startedAt = System.nanoTime();
        return chain.filter(exchange).doFinally(signal -> metrics.recordRequest(
                endpointOf(exchange),
                statusOf(exchange, signal),
                Duration.ofNanos(System.nanoTime() - startedAt)));
    }

    static String endpointOf(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route != null ? route.getId() : UNROUTED;
    }

    private static int statusOf(ServerWebExchange exchange, SignalType signal) {
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        if (status != null) {
            return status.value();
        }
        return switch (signal) {
            case ON_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR.value();
            case CANCEL -> CLIENT_CLOSED_REQUEST;
            default -> HttpStatus.OK.value();
        };
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
