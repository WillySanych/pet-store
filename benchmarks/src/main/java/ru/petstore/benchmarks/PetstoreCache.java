package ru.petstore.benchmarks;

import java.util.List;
import java.util.Map;
import ru.petstore.common.cache.RefreshableReferenceCache;
import ru.petstore.common.reference.ReferenceItem;

final class PetstoreCache implements ReferenceStore {

    private final RefreshableReferenceCache<String, ReferenceItem> cache;

    PetstoreCache(Map<String, ReferenceItem> data) {
        cache = new RefreshableReferenceCache<>("benchmark", () -> data);
        cache.refresh();
    }

    @Override
    public ReferenceItem get(String code) {
        return cache.get(code).orElse(null);
    }

    @Override
    public List<ReferenceItem> all() {
        return cache.all();
    }
}
