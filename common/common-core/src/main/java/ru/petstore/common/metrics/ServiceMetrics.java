package ru.petstore.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import ru.petstore.common.cache.RefreshableReferenceCache;

public class ServiceMetrics {

    public static final String REQUESTS = "petstore.requests";
    public static final String REQUEST_DURATION = "petstore.request.duration";
    public static final String ERRORS = "petstore.errors";
    public static final String CACHE_SIZE = "petstore.cache.size";
    public static final String CACHE_HITS = "petstore.cache.hits";
    public static final String CACHE_MISSES = "petstore.cache.misses";
    public static final String UPSTREAM_RETRIES = "petstore.upstream.retries";
    public static final String OVERLOAD_REJECTED = "petstore.overload.rejected";

    private final MeterRegistry registry;

    public ServiceMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRequest(String endpoint, int status, Duration duration) {
        Counter.builder(REQUESTS)
                .tag("endpoint", endpoint)
                .tag("status", String.valueOf(status))
                .tag("outcome", status < 400 ? "success" : "failure")
                .register(registry)
                .increment();

        Timer.builder(REQUEST_DURATION)
                .tag("endpoint", endpoint)
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    public void recordError(String type) {
        Counter.builder(ERRORS).tag("type", type).register(registry).increment();
    }

    public void recordUpstreamRetry(String upstream) {
        Counter.builder(UPSTREAM_RETRIES).tag("upstream", upstream).register(registry).increment();
    }

    public void recordOverloadRejected(String endpoint) {
        Counter.builder(OVERLOAD_REJECTED).tag("endpoint", endpoint).register(registry).increment();
    }

    public void bindCache(RefreshableReferenceCache<?, ?> cache) {
        Gauge.builder(CACHE_SIZE, cache, RefreshableReferenceCache::size)
                .tag("cache", cache.name())
                .register(registry);
        FunctionCounter.builder(CACHE_HITS, cache, RefreshableReferenceCache::hits)
                .tag("cache", cache.name())
                .register(registry);
        FunctionCounter.builder(CACHE_MISSES, cache, RefreshableReferenceCache::misses)
                .tag("cache", cache.name())
                .register(registry);
    }
}
