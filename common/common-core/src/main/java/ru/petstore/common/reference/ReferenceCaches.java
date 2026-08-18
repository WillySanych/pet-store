package ru.petstore.common.reference;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import ru.petstore.common.cache.RefreshableReferenceCache;

/**
 * Builds the reference caches every service declares. {@code ReferenceCacheRegistry} then picks
 * up each cache bean, warms it up, refreshes it and binds its metrics.
 */
public final class ReferenceCaches {

    private ReferenceCaches() {
    }

    public static RefreshableReferenceCache<String, ReferenceItem> of(
            String name, Supplier<? extends Collection<? extends ReferenceEntity>> loader) {
        return new RefreshableReferenceCache<>(name, () -> {
            Map<String, ReferenceItem> items = new LinkedHashMap<>();
            for (ReferenceEntity entity : loader.get()) {
                items.put(entity.getCode(),
                        new ReferenceItem(entity.getId(), entity.getCode(), entity.getName()));
            }
            return items;
        });
    }
}
