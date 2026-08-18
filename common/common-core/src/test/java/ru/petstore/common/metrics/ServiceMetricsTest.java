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

    private SimpleMeterRegistry simpleMeterRegistry;
    private ServiceMetrics serviceMetrics;

    @BeforeEach
    void setUp() {
        simpleMeterRegistry = new SimpleMeterRegistry();
        serviceMetrics = new ServiceMetrics(simpleMeterRegistry);
    }

    @Test
    @DisplayName("Успешные и неуспешные запросы разделяются лейблом outcome")
    void successAndFailureAreSplitByOutcomeTag() {
        serviceMetrics.recordRequest("/api/v1/products", 200, Duration.ofMillis(10));
        serviceMetrics.recordRequest("/api/v1/products", 500, Duration.ofMillis(20));

        double success = simpleMeterRegistry.get(ServiceMetrics.REQUESTS)
                .tag("outcome", "success").counter().count();
        double failure = simpleMeterRegistry.get(ServiceMetrics.REQUESTS)
                .tag("outcome", "failure").counter().count();

        assertThat(success).isEqualTo(1);
        assertThat(failure).isEqualTo(1);
    }

    @Test
    @DisplayName("Длительность запроса попадает в Timer")
    void requestDurationIsRecordedInTimer() {
        serviceMetrics.recordRequest("/api/v1/products", 200, Duration.ofMillis(50));

        var timer = simpleMeterRegistry.get(ServiceMetrics.REQUEST_DURATION)
                .tag("endpoint", "/api/v1/products").timer();

        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(50.0);
    }

    @Test
    @DisplayName("Ошибки считаются по типам")
    void errorsAreCountedByType() {
        serviceMetrics.recordError("validation");
        serviceMetrics.recordError("validation");
        serviceMetrics.recordError("internal");

        assertThat(simpleMeterRegistry.get(ServiceMetrics.ERRORS).tag("type", "validation").counter().count())
                .isEqualTo(2);
        assertThat(simpleMeterRegistry.get(ServiceMetrics.ERRORS).tag("type", "internal").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Метрики кеша отражают его фактическое состояние")
    void cacheMetricsReflectActualState() {
        var cache = new RefreshableReferenceCache<String, String>("categories",
                () -> Map.of("a", "Alpha", "b", "Bravo"));
        cache.refresh();
        serviceMetrics.bindCache(cache);

        cache.get("a");
        cache.get("missing");

        assertThat(simpleMeterRegistry.get(ServiceMetrics.CACHE_SIZE).tag("cache", "categories").gauge().value())
                .isEqualTo(2);
        assertThat(simpleMeterRegistry.get(ServiceMetrics.CACHE_HITS).tag("cache", "categories").functionCounter().count())
                .isEqualTo(1);
        assertThat(simpleMeterRegistry.get(ServiceMetrics.CACHE_MISSES).tag("cache", "categories").functionCounter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Метрики кеша читают живые значения, а не снимок на момент привязки")
    void cacheMetricsReadLiveValuesNotSnapshot() {
        var cache = new RefreshableReferenceCache<String, String>("brands",
                () -> Map.of("a", "Alpha"));
        cache.refresh();
        serviceMetrics.bindCache(cache);

        var gauge = simpleMeterRegistry.get(ServiceMetrics.CACHE_SIZE).tag("cache", "brands").gauge();
        assertThat(gauge.value()).isEqualTo(1);

        cache.get("a");
        cache.get("a");

        // FunctionCounter reads the same AtomicLong, so the value never lags behind
        assertThat(simpleMeterRegistry.get(ServiceMetrics.CACHE_HITS).tag("cache", "brands").functionCounter().count())
                .isEqualTo(2);
    }
}
