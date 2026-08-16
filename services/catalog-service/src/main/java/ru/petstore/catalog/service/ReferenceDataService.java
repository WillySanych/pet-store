package ru.petstore.catalog.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import ru.petstore.common.cache.RefreshableReferenceCache;
import ru.petstore.common.web.ServiceUnavailableException;

/**
 * The only way into the reference data. The read path never touches the database — everything
 * comes from the caches warmed up before the pod takes traffic.
 */
@Service
public class ReferenceDataService {

    private final Map<ReferenceType, RefreshableReferenceCache<String, ReferenceItem>> caches;

    public ReferenceDataService(Map<String, RefreshableReferenceCache<String, ReferenceItem>> beans) {
        var byType = new EnumMap<ReferenceType, RefreshableReferenceCache<String, ReferenceItem>>(
                ReferenceType.class);
        for (ReferenceType type : ReferenceType.values()) {
            RefreshableReferenceCache<String, ReferenceItem> cache = beans.get(type.cacheBeanName());
            if (cache == null) {
                throw new IllegalStateException("No cache bean '" + type.cacheBeanName()
                        + "' for reference type " + type + "; declared caches: " + beans.keySet());
            }
            byType.put(type, cache);
        }
        this.caches = byType;
    }

    public List<ReferenceItem> getAll(ReferenceType type) {
        return warm(type).all();
    }

    public ReferenceItem getRequired(ReferenceType type, String code) {
        return warm(type).get(code)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown " + type.code() + " code: " + code));
    }

    public Long getIdOrNull(ReferenceType type, String code) {
        return code == null || code.isBlank() ? null : getRequired(type, code).id();
    }

    private RefreshableReferenceCache<String, ReferenceItem> warm(ReferenceType type) {
        RefreshableReferenceCache<String, ReferenceItem> cache = caches.get(type);
        if (!cache.isWarmedUp()) {
            throw new ServiceUnavailableException(
                    "Reference data " + type.code() + " is not loaded yet");
        }
        return cache;
    }
}
