package ru.petstore.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class RequestTracingFilterTest {

    private static final String HEADER = RequestTracingFilter.REQUEST_ID_HEADER;

    private final RequestTracingFilter filter = new RequestTracingFilter(HEADER);

    private final AtomicReference<String> seenDownstream = new AtomicReference<>();

    private final WebFilterChain chain = exchange -> {
        seenDownstream.set(exchange.getRequest().getHeaders().getFirst(HEADER));
        return exchange.getResponse().setComplete();
    };

    @Test
    @DisplayName("Входящий идентификатор сохраняется, а не перезаписывается")
    void incomingRequestIdIsPreserved() {
        String incoming = UUID.randomUUID().toString();
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/products").header(HEADER, incoming));

        filter.filter(exchange, chain).block();

        assertThat(seenDownstream.get()).isEqualTo(incoming);
        assertThat(exchange.getResponse().getHeaders().getFirst(HEADER)).isEqualTo(incoming);
    }

    @Test
    @DisplayName("При отсутствии заголовка идентификатор генерируется")
    void requestIdIsGeneratedWhenHeaderAbsent() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products"));

        filter.filter(exchange, chain).block();

        assertThat(seenDownstream.get()).isNotBlank();
        assertThat(exchange.getResponse().getHeaders().getFirst(HEADER)).isEqualTo(seenDownstream.get());
    }

    @Test
    @DisplayName("Пустой заголовок трактуется как отсутствующий")
    void blankHeaderIsTreatedAsAbsent() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/products").header(HEADER, "   "));

        filter.filter(exchange, chain).block();

        assertThat(seenDownstream.get()).isNotBlank().doesNotContain(" ");
    }

    @Test
    @DisplayName("Идентификатор попадает в контекст Reactor — из него его берёт MDC")
    void requestIdIsWrittenToTheReactorContext() {
        String incoming = UUID.randomUUID().toString();
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/products").header(HEADER, incoming));
        var seenInContext = new AtomicReference<String>();
        WebFilterChain contextReadingChain = ignored -> Mono.deferContextual(context -> {
            seenInContext.set(context.get(RequestTracingFilter.MDC_KEY));
            return Mono.empty();
        });

        filter.filter(exchange, contextReadingChain).block();

        assertThat(seenInContext.get()).isEqualTo(incoming);
    }
}
