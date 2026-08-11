package ru.petstore.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestTracingFilterTest {

    private final RequestTracingFilter filter =
            new RequestTracingFilter(RequestTracingFilter.REQUEST_ID_HEADER);

    @Test
    @DisplayName("Входящий идентификатор сохраняется, а не перезаписывается")
    void incomingRequestIdIsPreserved() throws Exception {
        String incoming = UUID.randomUUID().toString();
        var request = new MockHttpServletRequest();
        request.addHeader(RequestTracingFilter.REQUEST_ID_HEADER, incoming);
        var response = new MockHttpServletResponse();
        var seenInMdc = new AtomicReference<String>();

        filter.doFilter(request, response,
                (req, res) -> seenInMdc.set(MDC.get(RequestTracingFilter.MDC_KEY)));

        // This is what makes tracing end-to-end: the gateway issued the id once
        assertThat(seenInMdc.get()).isEqualTo(incoming);
        assertThat(response.getHeader(RequestTracingFilter.REQUEST_ID_HEADER)).isEqualTo(incoming);
    }

    @Test
    @DisplayName("При отсутствии заголовка идентификатор генерируется")
    void requestIdIsGeneratedWhenHeaderAbsent() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var seenInMdc = new AtomicReference<String>();

        filter.doFilter(request, response,
                (req, res) -> seenInMdc.set(MDC.get(RequestTracingFilter.MDC_KEY)));

        assertThat(seenInMdc.get()).isNotBlank();
        assertThat(response.getHeader(RequestTracingFilter.REQUEST_ID_HEADER)).isEqualTo(seenInMdc.get());
    }

    @Test
    @DisplayName("Пустой заголовок трактуется как отсутствующий")
    void blankHeaderIsTreatedAsAbsent() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestTracingFilter.REQUEST_ID_HEADER, "   ");
        var response = new MockHttpServletResponse();
        var seenInMdc = new AtomicReference<String>();

        filter.doFilter(request, response,
                (req, res) -> seenInMdc.set(MDC.get(RequestTracingFilter.MDC_KEY)));

        assertThat(seenInMdc.get()).isNotBlank().doesNotContain(" ");
    }

    @Test
    @DisplayName("MDC очищается, даже если обработчик бросил исключение")
    void mdcIsClearedEvenWhenHandlerThrows() {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("handler failure");
        };

        try {
            filter.doFilter(request, response, failing);
        } catch (Exception ignored) {
            // expected
        }

        // Without the cleanup the id would leak into the next request on the same thread
        assertThat(MDC.get(RequestTracingFilter.MDC_KEY)).isNull();
    }
}
