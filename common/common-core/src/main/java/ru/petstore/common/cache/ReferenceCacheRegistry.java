package ru.petstore.common.cache;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import ru.petstore.common.metrics.ServiceMetrics;

/**
 * Warms up and periodically refreshes the reference caches of a service.
 *
 * <p>The refresh is not wrapped in ShedLock, unlike the other schedulers in the project:
 * it changes pod-local state only and must run on every replica.
 */
public class ReferenceCacheRegistry {

    private static final Logger log = LoggerFactory.getLogger(ReferenceCacheRegistry.class);

    private final List<RefreshableReferenceCache<?, ?>> caches;

    public ReferenceCacheRegistry(List<RefreshableReferenceCache<?, ?>> caches, ServiceMetrics metrics) {
        this.caches = caches;
        caches.forEach(metrics::bindCache);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        refreshAll();
    }

    @Scheduled(fixedDelayString = "${petstore.cache.refresh-interval:PT5M}",
            initialDelayString = "${petstore.cache.refresh-interval:PT5M}")
    public void refreshAll() {
        for (RefreshableReferenceCache<?, ?> cache : caches) {
            try {
                cache.refresh();
                log.debug("Cache {} refreshed, entries: {}", cache.name(), cache.size());
            } catch (RuntimeException e) {
                log.error("Failed to refresh cache {}", cache.name(), e);
            }
        }
    }

    public List<RefreshableReferenceCache<?, ?>> caches() {
        return caches;
    }

    public boolean allWarmedUp() {
        return caches.stream().allMatch(RefreshableReferenceCache::isWarmedUp);
    }
}
