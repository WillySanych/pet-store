package ru.petstore.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import ru.petstore.common.metrics.ServiceMetrics;

class RequestMetricsFilterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final RequestMetricsFilter filter =
            new RequestMetricsFilter(new ServiceMetrics(registry), "/actuator");

    @Test
    @DisplayName("Запрос считается под шаблоном пути")
    void requestIsCountedUnderThePathTemplate() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/products/42");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/products/{id}");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(registry.get(ServiceMetrics.REQUESTS)
                .tag("endpoint", "/api/v1/products/{id}")
                .counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Обращения к actuator не попадают в rps приложения")
    void actuatorTrafficIsNotCounted() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/prometheus"),
                new MockHttpServletResponse(), new MockFilterChain());

        assertThat(registry.find(ServiceMetrics.REQUESTS).counters()).isEmpty();
        assertThat(registry.find(ServiceMetrics.REQUEST_DURATION).timers()).isEmpty();
    }

    @Test
    @DisplayName("Префикс исключения берётся из настройки")
    void excludePrefixComesFromConfiguration() throws Exception {
        var filter = new RequestMetricsFilter(new ServiceMetrics(registry), "/manage");
        var request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/actuator/prometheus");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(new MockHttpServletRequest("GET", "/manage/prometheus"),
                new MockHttpServletResponse(), new MockFilterChain());

        assertThat(registry.get(ServiceMetrics.REQUESTS)
                .tag("endpoint", "/actuator/prometheus")
                .counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Пустой префикс исключения — ошибка, а не молча выключенные метрики")
    void blankExcludePrefixIsRejected() {
        assertThatThrownBy(() -> new RequestMetricsFilter(new ServiceMetrics(registry), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("petstore.metrics.exclude-prefix");
    }
}
