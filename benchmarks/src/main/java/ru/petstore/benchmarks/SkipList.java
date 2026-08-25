package ru.petstore.benchmarks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import ru.petstore.common.reference.ReferenceItem;

final class SkipList implements ReferenceStore {

    private final ConcurrentSkipListMap<String, ReferenceItem> map;

    SkipList(Map<String, ReferenceItem> data) {
        map = new ConcurrentSkipListMap<>(data);
    }

    @Override
    public ReferenceItem get(String code) {
        return map.get(code);
    }

    @Override
    public List<ReferenceItem> all() {
        return List.copyOf(map.values());
    }
}
