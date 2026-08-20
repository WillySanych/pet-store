package ru.petstore.common.grpc;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.UUID;
import org.slf4j.MDC;
import ru.petstore.common.web.RequestTracingFilter;

/**
 * The gRPC counterpart of {@link RequestTracingFilter}: takes the request id from the metadata or
 * generates one, and holds it in MDC around every callback of the call.
 */
public class RequestIdServerInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String incoming = headers.get(GrpcTracing.REQUEST_ID);
        String requestId = incoming == null || incoming.isBlank()
                ? UUID.randomUUID().toString()
                : incoming;

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
                withRequestId(requestId, () -> next.startCall(call, headers))) {

            @Override
            public void onMessage(ReqT message) {
                withRequestId(requestId, () -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                withRequestId(requestId, super::onHalfClose);
            }

            @Override
            public void onCancel() {
                withRequestId(requestId, super::onCancel);
            }

            @Override
            public void onComplete() {
                withRequestId(requestId, super::onComplete);
            }

            @Override
            public void onReady() {
                withRequestId(requestId, super::onReady);
            }
        };
    }

    private static void withRequestId(String requestId, Runnable action) {
        withRequestId(requestId, () -> {
            action.run();
            return null;
        });
    }

    private static <T> T withRequestId(String requestId, java.util.function.Supplier<T> action) {
        MDC.put(RequestTracingFilter.MDC_KEY, requestId);
        try {
            return action.get();
        } finally {
            MDC.remove(RequestTracingFilter.MDC_KEY);
        }
    }
}
