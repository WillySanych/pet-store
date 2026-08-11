package ru.petstore.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import ru.petstore.common.metrics.ServiceMetrics;

/**
 * Counts requests broken down by status and measures their duration; RPS is derived from
 * the counter in Grafana via {@code rate()}.
 */
public class RequestMetricsFilter extends OncePerRequestFilter implements Ordered {

    private static final String UNKNOWN = "unknown";

    private final ServiceMetrics metrics;

    public RequestMetricsFilter(ServiceMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            metrics.recordRequest(
                    endpointTemplate(request),
                    response.getStatus(),
                    Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    private String endpointTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : UNKNOWN;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
