package ru.petstore.common.web;

import java.time.Instant;

/** Common error format shared by all services. */
public record ApiError(
        String code,
        String message,
        String requestId,
        Instant timestamp) {

    public static ApiError of(String code, String message, String requestId) {
        return new ApiError(code, message, requestId, Instant.now());
    }
}
