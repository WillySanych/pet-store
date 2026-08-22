package ru.petstore.gateway.web;

import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

public class RequestTracingFilter implements WebFilter, Ordered {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private final String headerName;

    public RequestTracingFilter(String headerName) {
        this.headerName = headerName;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(headerName);
        String requestId = incoming == null || incoming.isBlank() ? UUID.randomUUID().toString() : incoming;

        ServerWebExchange traced = exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(headerName, requestId)))
                .build();

        ServerHttpResponse response = traced.getResponse();
        response.beforeCommit(() -> {
            response.getHeaders().set(headerName, requestId);
            return Mono.empty();
        });

        return chain.filter(traced).contextWrite(Context.of(MDC_KEY, requestId));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
