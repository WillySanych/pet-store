package ru.petstore.gateway.web;

import java.time.Instant;

public record ApiErrorResponse(
        String code,
        String message,
        String requestId,
        Instant timestamp) {

    public static ApiErrorResponse of(String code, String message, String requestId) {
        return new ApiErrorResponse(code, message, requestId, Instant.now());
    }
}
