package ru.petstore.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.petstore.gateway.metrics.GatewayMetrics;

public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final SemaphoreRateLimiter limiter;
    private final GatewayMetrics metrics;
    private final ObjectMapper objectMapper;
    private final String headerName;
    private final String retryAfterSeconds;

    public RateLimitFilter(SemaphoreRateLimiter limiter, GatewayMetrics metrics, ObjectMapper objectMapper,
                           String headerName, long retryAfterSeconds) {
        this.limiter = limiter;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.headerName = headerName;
        this.retryAfterSeconds = String.valueOf(Math.max(1, retryAfterSeconds));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return limiter.tryAcquire() ? chain.filter(exchange) : reject(exchange);
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        String endpoint = RequestMetricsFilter.endpointOf(exchange);
        metrics.recordRateLimited(endpoint);
        log.warn("Request to {} rejected: the gateway is over {} request(s) per window",
                endpoint, limiter.limitForPeriod());

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, retryAfterSeconds);

        DataBuffer body = response.bufferFactory().wrap(errorBody(
                exchange.getRequest().getHeaders().getFirst(headerName)));
        return response.writeWith(Mono.just(body));
    }

    private byte[] errorBody(String requestId) {
        ApiErrorResponse error = ApiErrorResponse.of(
                "RATE_LIMITED", "Too many requests, retry later", requestId);
        try {
            return objectMapper.writeValueAsBytes(error);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize the rate limit error", e);
            return "{\"code\":\"RATE_LIMITED\"}".getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
