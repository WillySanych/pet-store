package ru.petstore.order.client;

import io.grpc.StatusRuntimeException;

/** Turns a gRPC status into the two kinds the order cares about: retry it, or give up on it. */
final class GrpcErrors {

    private GrpcErrors() {
    }

    static RuntimeException translate(String upstream, StatusRuntimeException e) {
        return switch (e.getStatus().getCode()) {
            case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, ABORTED ->
                    new UpstreamUnavailableException(upstream, describe(e), e);
            default -> new UpstreamFailedException(upstream, describe(e), e);
        };
    }

    private static String describe(StatusRuntimeException e) {
        String description = e.getStatus().getDescription();
        return description == null
                ? e.getStatus().getCode().name()
                : e.getStatus().getCode().name() + " (" + description + ")";
    }
}
