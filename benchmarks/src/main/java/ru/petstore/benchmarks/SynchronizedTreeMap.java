package ru.petstore.benchmarks;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import ru.petstore.common.reference.ReferenceItem;

final class SynchronizedTreeMap implements ReferenceStore {

    private final SortedMap<String, ReferenceItem> map;

    SynchronizedTreeMap(Map<String, ReferenceItem> data) {
        map = Collections.synchronizedSortedMap(new TreeMap<>(data));
    }

    @Override
    public ReferenceItem get(String code) {
        return map.get(code);
    }

    @Override
    public List<ReferenceItem> all() {
        synchronized (map) {
            return List.copyOf(map.values());
        }
    }
}
