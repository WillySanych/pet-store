package ru.petstore.order.web;

import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.common.web.ApiErrorResponse;
import ru.petstore.common.web.GlobalExceptionHandler;
import ru.petstore.common.web.RequestTracingFilter;
import ru.petstore.order.client.UpstreamFailedException;
import ru.petstore.order.client.UpstreamUnavailableException;
import ru.petstore.order.service.OrderRejectedException;
import ru.petstore.order.service.OrderStateException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OrderExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderExceptionHandler.class);

    private final ServiceMetrics serviceMetrics;

    public OrderExceptionHandler(ServiceMetrics serviceMetrics) {
        this.serviceMetrics = serviceMetrics;
    }

    @ExceptionHandler(OrderRejectedException.class)
    public ResponseEntity<ApiErrorResponse> handleRejected(OrderRejectedException e) {
        serviceMetrics.recordError("order_rejected");
        log.warn("Order refused ({}): {}", e.getCode(), e.getMessage());
        return body(HttpStatus.UNPROCESSABLE_ENTITY, e.getCode(), e.getMessage());
    }

    @ExceptionHandler(OrderStateException.class)
    public ResponseEntity<ApiErrorResponse> handleState(OrderStateException e) {
        serviceMetrics.recordError("order_state");
        log.warn("Transition refused: {}", e.getMessage());
        return body(HttpStatus.CONFLICT, "ORDER_STATE", e.getMessage());
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnavailable(UpstreamUnavailableException e) {
        serviceMetrics.recordError("upstream_unavailable");
        log.error("Upstream {} unavailable", e.getUpstream(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, GlobalExceptionHandler.RETRY_AFTER_SECONDS)
                .body(ApiErrorResponse.of("UPSTREAM_UNAVAILABLE", e.getMessage(), requestId()));
    }

    @ExceptionHandler(UpstreamFailedException.class)
    public ResponseEntity<ApiErrorResponse> handleUpstreamFailed(UpstreamFailedException e) {
        serviceMetrics.recordError("upstream_failed");
        log.error("Upstream {} answered with an error", e.getUpstream(), e);
        return body(HttpStatus.BAD_GATEWAY, "UPSTREAM_FAILED", e.getMessage());
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimited(RequestNotPermitted e) {
        serviceMetrics.recordOverloadRejected("/api/v1/orders");
        serviceMetrics.recordError("rate_limited");
        log.warn("Order creation rejected by the rate limiter: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, GlobalExceptionHandler.RETRY_AFTER_SECONDS)
                .body(ApiErrorResponse.of("RATE_LIMITED", "Too many orders, retry later", requestId()));
    }

    private ResponseEntity<ApiErrorResponse> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code, message, requestId()));
    }

    private static String requestId() {
        return RequestTracingFilter.currentRequestId();
    }
}
