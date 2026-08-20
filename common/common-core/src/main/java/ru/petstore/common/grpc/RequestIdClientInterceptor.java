package ru.petstore.common.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.slf4j.MDC;
import ru.petstore.common.web.RequestTracingFilter;

/** Puts the current request id into the outgoing call, so the trace continues on the other side. */
public class RequestIdClientInterceptor implements ClientInterceptor {

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                String requestId = MDC.get(RequestTracingFilter.MDC_KEY);
                if (requestId != null && !requestId.isBlank()) {
                    headers.put(GrpcTracing.REQUEST_ID, requestId);
                }
                super.start(responseListener, headers);
            }
        };
    }
}
