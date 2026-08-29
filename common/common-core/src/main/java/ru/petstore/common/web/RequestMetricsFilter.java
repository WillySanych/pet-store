package ru.petstore.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.petstore.common.metrics.ServiceMetrics;

/**
 * Counts requests broken down by status and measures their duration.
 */
public class RequestMetricsFilter extends OncePerRequestFilter implements Ordered {

    private final ServiceMetrics serviceMetrics;
    private final String excludePrefix;

    public RequestMetricsFilter(ServiceMetrics serviceMetrics, String excludePrefix) {
        if (!StringUtils.hasText(excludePrefix)) {
            throw new IllegalArgumentException("petstore.metrics.exclude-prefix must not be blank");
        }
        this.serviceMetrics = serviceMetrics;
        this.excludePrefix = excludePrefix;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(excludePrefix);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            serviceMetrics.recordRequest(
                    EndpointTemplate.of(request),
                    response.getStatus(),
                    Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
