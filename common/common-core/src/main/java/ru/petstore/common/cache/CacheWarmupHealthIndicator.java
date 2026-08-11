package ru.petstore.common.cache;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Keeps readiness DOWN until the reference caches are warmed up: a pod with a cold cache
 * must not receive traffic, otherwise the first requests bypass the cache and hit the database.
 */
public class CacheWarmupHealthIndicator implements HealthIndicator {

    private final ReferenceCacheRegistry registry;

    public CacheWarmupHealthIndicator(ReferenceCacheRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        Health.Builder builder = registry.allWarmedUp() ? Health.up() : Health.outOfService();
        registry.caches().forEach(cache ->
                builder.withDetail(cache.name(), cache.isWarmedUp() ? cache.size() + " entries" : "not warmed up"));
        return builder.build();
    }
}
