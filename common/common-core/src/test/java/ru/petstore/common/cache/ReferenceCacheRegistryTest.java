package ru.petstore.common.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import ru.petstore.common.metrics.ServiceMetrics;

class ReferenceCacheRegistryTest {

    private final ServiceMetrics metrics = new ServiceMetrics(new SimpleMeterRegistry());

    @Test
    @DisplayName("Упавший загрузчик не обнуляет уже загруженный кеш")
    void failingLoaderKeepsPreviousSnapshot() {
        var failNext = new AtomicBoolean(false);
        var cache = new RefreshableReferenceCache<String, String>("categories", () -> {
            if (failNext.get()) {
                throw new IllegalStateException("database is unavailable");
            }
            return Map.of("a", "Alpha", "b", "Bravo");
        });
        var registry = new ReferenceCacheRegistry(List.of(cache), metrics);

        registry.refreshAll();
        assertThat(cache.size()).isEqualTo(2);

        failNext.set(true);
        registry.refreshAll();

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("a")).contains("Alpha");
        assertThat(cache.isWarmedUp()).isTrue();
    }

    @Test
    @DisplayName("Сбой одного кеша не мешает остальным")
    void failureOfOneCacheDoesNotAffectOthers() {
        var broken = new RefreshableReferenceCache<String, String>("broken", () -> {
            throw new IllegalStateException("database is unavailable");
        });
        var healthy = new RefreshableReferenceCache<>("healthy", () -> Map.of("a", "Alpha"));
        var registry = new ReferenceCacheRegistry(List.of(broken, healthy), metrics);

        registry.refreshAll();

        assertThat(broken.isWarmedUp()).isFalse();
        assertThat(healthy.isWarmedUp()).isTrue();
        assertThat(healthy.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("До прогрева readiness не готов")
    void readinessIsNotReadyBeforeWarmUp() {
        var cache = new RefreshableReferenceCache<>("categories", () -> Map.of("a", "Alpha"));
        var registry = new ReferenceCacheRegistry(List.of(cache), metrics);
        var indicator = new CacheWarmupHealthIndicator(registry);

        assertThat(registry.allWarmedUp()).isFalse();
        assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);

        registry.refreshAll();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getDetails()).containsEntry("categories", "1 entries");
    }

    @Test
    @DisplayName("Без кешей сервис готов сразу")
    void readyImmediatelyWhenNoCaches() {
        var registry = new ReferenceCacheRegistry(List.of(), metrics);
        var indicator = new CacheWarmupHealthIndicator(registry);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }
}
