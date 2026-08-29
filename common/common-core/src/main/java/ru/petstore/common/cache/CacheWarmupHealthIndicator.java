package ru.petstore.common.cache;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Keeps readiness down until the reference caches are warmed up.
 */
public class CacheWarmupHealthIndicator implements HealthIndicator {

    private final ReferenceCacheRegistry referenceCacheRegistry;

    public CacheWarmupHealthIndicator(ReferenceCacheRegistry referenceCacheRegistry) {
        this.referenceCacheRegistry = referenceCacheRegistry;
    }

    @Override
    public Health health() {
        Health.Builder builder = referenceCacheRegistry.allWarmedUp() ? Health.up() : Health.outOfService();
        referenceCacheRegistry.caches().forEach(cache ->
                builder.withDetail(cache.name(), cache.isWarmedUp() ? cache.size() + " entries" : "not warmed up"));
        return builder.build();
    }
}
