package ru.petstore.common.web;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import ru.petstore.common.metrics.ServiceMetrics;

/**
 * Holds a bulkhead permit for the whole request: taken once the handler is known, given back when
 * the response is done.
 */
public class OverloadInterceptor implements AsyncHandlerInterceptor {

    private final Bulkhead bulkhead;
    private final ServiceMetrics serviceMetrics;

    public OverloadInterceptor(Bulkhead bulkhead, ServiceMetrics serviceMetrics) {
        this.bulkhead = bulkhead;
        this.serviceMetrics = serviceMetrics;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        try {
            bulkhead.acquirePermission();
        } catch (BulkheadFullException e) {
            serviceMetrics.recordOverloadRejected(EndpointTemplate.of(request));
            throw e;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        bulkhead.onComplete();
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) {
        bulkhead.onComplete();
    }
}
