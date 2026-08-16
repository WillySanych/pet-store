package ru.petstore.common.web;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.common.overload.OverloadedException;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Seconds before retrying a 503. */
    private static final String RETRY_AFTER_SECONDS = "5";

    private final ServiceMetrics serviceMetrics;

    public GlobalExceptionHandler(ServiceMetrics serviceMetrics) {
        this.serviceMetrics = serviceMetrics;
    }

    @ExceptionHandler(OverloadedException.class)
    public ResponseEntity<ApiErrorResponse> handleOverloaded(OverloadedException e) {
        serviceMetrics.recordError("overloaded");
        log.warn("Request rejected due to overload: {}", e.endpoint());
        return body(HttpStatus.TOO_MANY_REQUESTS, "OVERLOADED", e.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException e) {
        serviceMetrics.recordError("not_found");
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnavailable(ServiceUnavailableException e) {
        serviceMetrics.recordError("unavailable");
        log.warn("Request rejected, service not ready: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(ApiErrorResponse.of("SERVICE_UNAVAILABLE", e.getMessage(), requestId()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        serviceMetrics.recordError("validation");
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        serviceMetrics.recordError("bad_request");
        log.warn("Bad request: {}", e.getMessage(), e);
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception e) {
        serviceMetrics.recordError("internal");
        log.error("Unhandled error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal service error");
    }

    /** Expands validation errors into a per-field list. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        String details = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("invalid request");
        serviceMetrics.recordError("validation");
        return new ResponseEntity<>(
                ApiErrorResponse.of("VALIDATION_FAILED", details, requestId()), headers, status);
    }

    /** Every standard Spring MVC exception passes through here and gets the common format. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object requestBody,
                                                             HttpHeaders headers,
                                                             HttpStatusCode status,
                                                             WebRequest request) {
        serviceMetrics.recordError(status.is4xxClientError() ? "client_error" : "server_error");
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String code = resolved != null ? resolved.name() : "ERROR";
        return new ResponseEntity<>(
                ApiErrorResponse.of(code, e.getMessage(), requestId()), headers, status);
    }

    private ResponseEntity<ApiErrorResponse> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code, message, requestId()));
    }

    private String requestId() {
        return MDC.get(RequestTracingFilter.MDC_KEY);
    }
}
