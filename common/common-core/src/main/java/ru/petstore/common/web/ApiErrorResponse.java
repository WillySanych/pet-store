package ru.petstore.common.web;

import java.time.Instant;

/** Common error format shared by all services. */
public record ApiErrorResponse(
        String code,
        String message,
        String requestId,
        Instant timestamp) {

    public static ApiErrorResponse of(String code, String message, String requestId) {
        return new ApiErrorResponse(code, message, requestId, Instant.now());
    }
}
