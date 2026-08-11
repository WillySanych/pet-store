package ru.petstore.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.petstore.common.cache.RefreshableReferenceCache;

class ServiceMetricsTest {

    private SimpleMeterRegistry registry;
    private ServiceMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ServiceMetrics(registry);
    }

    @Test
    @DisplayName("Успешные и неуспешные запросы разделяются лейблом outcome")
    void successAndFailureAreSplitByOutcomeTag() {
        metrics.recordRequest("/api/v1/products", 200, Duration.ofMillis(10));
        metrics.recordRequest("/api/v1/products", 500, Duration.ofMillis(20));

        double success = registry.get(ServiceMetrics.REQUESTS)
                .tag("outcome", "success").counter().count();
        double failure = registry.get(ServiceMetrics.REQUESTS)
                .tag("outcome", "failure").counter().count();

        assertThat(success).isEqualTo(1);
        assertThat(failure).isEqualTo(1);
    }

    @Test
    @DisplayName("Длительность запроса попадает в Timer")
    void requestDurationIsRecordedInTimer() {
        metrics.recordRequest("/api/v1/products", 200, Duration.ofMillis(50));

        var timer = registry.get(ServiceMetrics.REQUEST_DURATION)
                .tag("endpoint", "/api/v1/products").timer();

        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(50.0);
    }

    @Test
    @DisplayName("Ошибки считаются по типам")
    void errorsAreCountedByType() {
        metrics.recordError("validation");
        metrics.recordError("validation");
        metrics.recordError("internal");

        assertThat(registry.get(ServiceMetrics.ERRORS).tag("type", "validation").counter().count())
                .isEqualTo(2);
        assertThat(registry.get(ServiceMetrics.ERRORS).tag("type", "internal").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Метрики кеша отражают его фактическое состояние")
    void cacheMetricsReflectActualState() {
        var cache = new RefreshableReferenceCache<String, String>("categories",
                () -> Map.of("a", "Alpha", "b", "Bravo"));
        cache.refresh();
        metrics.bindCache(cache);

        cache.get("a");
        cache.get("missing");

        assertThat(registry.get(ServiceMetrics.CACHE_SIZE).tag("cache", "categories").gauge().value())
                .isEqualTo(2);
        assertThat(registry.get(ServiceMetrics.CACHE_HITS).tag("cache", "categories").functionCounter().count())
                .isEqualTo(1);
        assertThat(registry.get(ServiceMetrics.CACHE_MISSES).tag("cache", "categories").functionCounter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Метрики кеша читают живые значения, а не снимок на момент привязки")
    void cacheMetricsReadLiveValuesNotSnapshot() {
        var cache = new RefreshableReferenceCache<String, String>("brands",
                () -> Map.of("a", "Alpha"));
        cache.refresh();
        metrics.bindCache(cache);

        var gauge = registry.get(ServiceMetrics.CACHE_SIZE).tag("cache", "brands").gauge();
        assertThat(gauge.value()).isEqualTo(1);

        cache.get("a");
        cache.get("a");

        // FunctionCounter reads the same AtomicLong, so the value never lags behind
        assertThat(registry.get(ServiceMetrics.CACHE_HITS).tag("cache", "brands").functionCounter().count())
                .isEqualTo(2);
    }
}
