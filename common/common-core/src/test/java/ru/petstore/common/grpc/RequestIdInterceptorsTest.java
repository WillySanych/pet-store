package ru.petstore.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import ru.petstore.common.web.RequestTracingFilter;

class RequestIdInterceptorsTest {

    private static final MethodDescriptor<String, String> METHOD = MethodDescriptor.<String, String>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("petstore.Test/Call")
            .setRequestMarshaller(new StringMarshaller())
            .setResponseMarshaller(new StringMarshaller())
            .build();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("Клиентский интерцептор кладёт requestId в метаданные вызова")
    @SuppressWarnings("unchecked")
    void clientSendsRequestId() {
        Channel channel = mock(Channel.class);
        ClientCall<String, String> delegate = mock(ClientCall.class);
        doReturn(delegate).when(channel).newCall(any(), any());
        MDC.put(RequestTracingFilter.MDC_KEY, "trace-1");

        new RequestIdClientInterceptor().interceptCall(METHOD, CallOptions.DEFAULT, channel)
                .start(mock(ClientCall.Listener.class), new Metadata());

        ArgumentCaptor<Metadata> headers = ArgumentCaptor.forClass(Metadata.class);
        verify(delegate).start(any(), headers.capture());
        assertThat(headers.getValue().get(GrpcTracing.REQUEST_ID)).isEqualTo("trace-1");
    }

    @Test
    @DisplayName("Без контекста заголовок не ставится: пустой идентификатор хуже отсутствующего")
    @SuppressWarnings("unchecked")
    void clientWithoutContextSendsNothing() {
        Channel channel = mock(Channel.class);
        ClientCall<String, String> delegate = mock(ClientCall.class);
        doReturn(delegate).when(channel).newCall(any(), any());

        new RequestIdClientInterceptor().interceptCall(METHOD, CallOptions.DEFAULT, channel)
                .start(mock(ClientCall.Listener.class), new Metadata());

        ArgumentCaptor<Metadata> headers = ArgumentCaptor.forClass(Metadata.class);
        verify(delegate).start(any(), headers.capture());
        assertThat(headers.getValue().get(GrpcTracing.REQUEST_ID)).isNull();
    }

    @Test
    @DisplayName("Серверный интерцептор кладёт присланный requestId в MDC на время обработки")
    void serverPutsIncomingRequestIdIntoMdc() {
        Metadata headers = new Metadata();
        headers.put(GrpcTracing.REQUEST_ID, "trace-1");

        AtomicReference<String> seen = new AtomicReference<>();
        listenerFor(headers, seen).onHalfClose();

        assertThat(seen.get()).isEqualTo("trace-1");
        assertThat(MDC.get(RequestTracingFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("Без заголовка идентификатор генерируется: вызов не остаётся без следа")
    void serverGeneratesRequestIdWhenAbsent() {
        AtomicReference<String> seen = new AtomicReference<>();
        listenerFor(new Metadata(), seen).onHalfClose();

        assertThat(seen.get()).isNotBlank();
    }

    @SuppressWarnings("unchecked")
    private static ServerCall.Listener<String> listenerFor(Metadata headers, AtomicReference<String> seen) {
        ServerCallHandler<String, String> handler = (call, metadata) -> new ServerCall.Listener<>() {
            @Override
            public void onHalfClose() {
                seen.set(MDC.get(RequestTracingFilter.MDC_KEY));
            }
        };
        return new RequestIdServerInterceptor()
                .interceptCall(mock(ServerCall.class), headers, handler);
    }

    private static final class StringMarshaller implements MethodDescriptor.Marshaller<String> {

        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
