package ru.petstore.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import ru.petstore.gateway.web.SemaphoreRateLimiter;

public class GatewayMetrics {

    public static final String REQUESTS = "petstore.requests";
    public static final String REQUEST_DURATION = "petstore.request.duration";
    public static final String OVERLOAD_REJECTED = "petstore.overload.rejected";
    public static final String RATE_LIMIT_AVAILABLE = "petstore.ratelimit.available";

    private final MeterRegistry meterRegistry;

    public GatewayMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordRequest(String endpoint, int status, Duration duration) {
        Counter.builder(REQUESTS)
                .tag("endpoint", endpoint)
                .tag("status", String.valueOf(status))
                .tag("outcome", status < 400 ? "success" : "failure")
                .register(meterRegistry)
                .increment();

        Timer.builder(REQUEST_DURATION)
                .tag("endpoint", endpoint)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(duration);
    }

    public void recordRateLimited(String endpoint) {
        Counter.builder(OVERLOAD_REJECTED).tag("endpoint", endpoint).register(meterRegistry).increment();
    }

    public void bindRateLimiter(SemaphoreRateLimiter limiter) {
        Gauge.builder(RATE_LIMIT_AVAILABLE, limiter, SemaphoreRateLimiter::availablePermits)
                .register(meterRegistry);
    }
}
