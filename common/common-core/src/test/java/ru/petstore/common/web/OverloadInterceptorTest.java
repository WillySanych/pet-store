package ru.petstore.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import ru.petstore.common.metrics.ServiceMetrics;

@ExtendWith(MockitoExtension.class)
class OverloadInterceptorTest {

    private static final String TEMPLATE = "/api/v1/products/{id}";

    @Mock
    private ServiceMetrics serviceMetrics;

    private static Bulkhead bulkheadOf(int permits) {
        return Bulkhead.of("test", BulkheadConfig.custom()
                .maxConcurrentCalls(permits)
                .maxWaitDuration(Duration.ZERO)
                .build());
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/v1/products/42");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, TEMPLATE);
        return request;
    }

    @Test
    @DisplayName("В пределах лимита запрос проходит")
    void requestPassesWithinTheLimit() {
        var bulkhead = bulkheadOf(1);
        var interceptor = new OverloadInterceptor(bulkhead, serviceMetrics);

        assertThat(interceptor.preHandle(request(), new MockHttpServletResponse(), new Object())).isTrue();

        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isZero();
        verify(serviceMetrics, never()).recordOverloadRejected(TEMPLATE);
    }

    @Test
    @DisplayName("Разрешение возвращается после ответа")
    void permitIsGivenBackAfterTheResponse() {
        var bulkhead = bulkheadOf(1);
        var interceptor = new OverloadInterceptor(bulkhead, serviceMetrics);
        interceptor.preHandle(request(), new MockHttpServletResponse(), new Object());

        interceptor.afterCompletion(request(), new MockHttpServletResponse(), new Object(), null);

        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isOne();
    }

    @Test
    @DisplayName("Сверх лимита запрос отклоняется и считается по шаблону пути")
    void requestOverTheLimitIsRejectedAndCounted() {
        var bulkhead = bulkheadOf(1);
        var interceptor = new OverloadInterceptor(bulkhead, serviceMetrics);
        interceptor.preHandle(request(), new MockHttpServletResponse(), new Object());

        assertThatThrownBy(() ->
                interceptor.preHandle(request(), new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BulkheadFullException.class);

        verify(serviceMetrics).recordOverloadRejected(TEMPLATE);
    }
}
