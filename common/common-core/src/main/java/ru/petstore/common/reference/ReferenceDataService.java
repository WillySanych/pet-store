package ru.petstore.common.reference;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.petstore.common.cache.RefreshableReferenceCache;
import ru.petstore.common.web.ServiceUnavailableException;

/**
 * The only way into the reference data of a service.
 * Everything comes from the caches warmed up before the app takes traffic.
 */
public class ReferenceDataService {

    private final Map<ReferenceKind, RefreshableReferenceCache<String, ReferenceItem>> caches;

    public ReferenceDataService(ReferenceKind[] kinds,
                                Map<String, RefreshableReferenceCache<String, ReferenceItem>> beans) {
        Map<ReferenceKind, RefreshableReferenceCache<String, ReferenceItem>> byKind = new HashMap<>();
        for (ReferenceKind kind : kinds) {
            RefreshableReferenceCache<String, ReferenceItem> cache = beans.get(kind.cacheBeanName());
            if (cache == null) {
                throw new IllegalStateException("No cache bean '" + kind.cacheBeanName()
                        + "' for reference type " + kind.name() + "; declared caches: " + beans.keySet());
            }
            byKind.put(kind, cache);
        }
        this.caches = Map.copyOf(byKind);
    }

    public List<ReferenceItem> getAll(ReferenceKind kind) {
        return warm(kind).all();
    }

    public ReferenceItem getRequired(ReferenceKind kind, String code) {
        return warm(kind).get(code)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown " + kind.code() + " code: " + code));
    }

    public Long getIdOrNull(ReferenceKind kind, String code) {
        return code == null || code.isBlank() ? null : getRequired(kind, code).id();
    }

    private RefreshableReferenceCache<String, ReferenceItem> warm(ReferenceKind kind) {
        RefreshableReferenceCache<String, ReferenceItem> cache = caches.get(kind);
        if (cache == null) {
            throw new IllegalArgumentException("Reference type " + kind.name() + " is not declared here");
        }
        if (!cache.isWarmedUp()) {
            throw new ServiceUnavailableException(
                    "Reference data " + kind.code() + " is not loaded yet");
        }
        return cache;
    }
}
