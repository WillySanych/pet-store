package ru.petstore.order.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import ru.petstore.common.metrics.ServiceMetrics;

/**
 * One upstream behind Retry → CircuitBreaker → TimeLimiter, timeout per attempt, repeat only for
 * {@link UpstreamUnavailableException}.
 */
public class UpstreamCall {

    private final String upstream;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final UpstreamExecutor executor;

    public UpstreamCall(String upstream, Retry retry, CircuitBreaker circuitBreaker,
                        TimeLimiter timeLimiter, UpstreamExecutor executor, ServiceMetrics metrics) {
        this.upstream = upstream;
        this.retry = retry;
        this.circuitBreaker = circuitBreaker;
        this.timeLimiter = timeLimiter;
        this.executor = executor;
        retry.getEventPublisher().onRetry(event -> metrics.recordUpstreamRetry(upstream));
    }

    public <T> T call(Supplier<T> action) {
        Supplier<T> attempt = () -> withTimeout(action);
        Supplier<T> guarded = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, attempt));
        try {
            return guarded.get();
        } catch (CallNotPermittedException e) {
            throw new UpstreamUnavailableException(upstream, "circuit breaker is open", e);
        }
    }

    private <T> T withTimeout(Supplier<T> action) {
        try {
            return timeLimiter.executeFutureSupplier(() -> executor.submit(action));
        } catch (TimeoutException e) {
            throw new UpstreamUnavailableException(upstream, "call timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailableException(upstream, "call interrupted", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new UpstreamUnavailableException(upstream, String.valueOf(e.getMessage()), e);
        }
    }

    public String upstream() {
        return upstream;
    }
}
