package ru.petstore.common.grpc;

import io.grpc.Metadata;
import ru.petstore.common.web.RequestTracingFilter;

/** The metadata key the request id travels in; gRPC lowercases header names itself. */
public final class GrpcTracing {

    public static final Metadata.Key<String> REQUEST_ID = Metadata.Key.of(
            RequestTracingFilter.REQUEST_ID_HEADER.toLowerCase(java.util.Locale.ROOT),
            Metadata.ASCII_STRING_MARSHALLER);

    private GrpcTracing() {
    }
}
