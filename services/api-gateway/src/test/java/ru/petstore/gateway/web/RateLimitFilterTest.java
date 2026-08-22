package ru.petstore.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.petstore.gateway.web.RoutedExchanges.routed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import ru.petstore.gateway.metrics.GatewayMetrics;

class RateLimitFilterTest {

    private static final String REQUEST_ID = "11111111-2222-3333-4444-555555555555";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GatewayMetrics metrics = new GatewayMetrics(registry);
    private final AtomicBoolean chainCalled = new AtomicBoolean();
    private final GatewayFilterChain chain = exchange -> {
        chainCalled.set(true);
        return Mono.empty();
    };

    private RateLimitFilter filterWith(SemaphoreRateLimiter limiter) {
        return new RateLimitFilter(limiter, metrics, new ObjectMapper().findAndRegisterModules(),
                RequestTracingFilter.REQUEST_ID_HEADER, 1);
    }

    private static MockServerWebExchange orderExchange() {
        return routed(MockServerHttpRequest.post("/api/v1/orders")
                .header(RequestTracingFilter.REQUEST_ID_HEADER, REQUEST_ID), "order");
    }

    @Test
    @DisplayName("В пределах лимита запрос идёт дальше по цепочке")
    void requestWithinTheLimitIsForwarded() {
        filterWith(new SemaphoreRateLimiter(1)).filter(orderExchange(), chain).block();

        assertThat(chainCalled).isTrue();
    }

    @Test
    @DisplayName("Сверх лимита — 429 с Retry-After и телом сервисного формата")
    void burstIsRejectedWithTooManyRequests() {
        var limiter = new SemaphoreRateLimiter(1);
        limiter.tryAcquire();
        var exchange = orderExchange();

        filterWith(limiter).filter(exchange, chain).block();

        assertThat(chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("RATE_LIMITED")
                .contains(REQUEST_ID);
    }

    @Test
    @DisplayName("Отклонённый запрос виден в метрике перегрузки под меткой маршрута")
    void rejectionIsCounted() {
        var limiter = new SemaphoreRateLimiter(1);
        limiter.tryAcquire();

        filterWith(limiter).filter(orderExchange(), chain).block();

        assertThat(registry.get(GatewayMetrics.OVERLOAD_REJECTED)
                .tag("endpoint", "order")
                .counter().count()).isEqualTo(1);
    }
}
