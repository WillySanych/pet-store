package ru.petstore.catalog.config;

import io.grpc.ServerInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.context.annotation.Configuration;
import ru.petstore.common.grpc.RequestIdServerInterceptor;

/** Continues the trace of order-service: its request id ends up in the MDC of the gRPC call. */
@Configuration
public class GrpcTracingConfig {

    @GrpcGlobalServerInterceptor
    public ServerInterceptor requestIdServerInterceptor() {
        return new RequestIdServerInterceptor();
    }
}
