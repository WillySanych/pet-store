package ru.petstore.benchmarks;

import java.util.List;
import java.util.Map;
import ru.petstore.common.reference.ReferenceItem;

public interface ReferenceStore {

    enum Kind {
        PETSTORE,
        SYNCHRONIZED_TREE_MAP,
        SKIP_LIST
    }

    static ReferenceStore of(Kind kind, Map<String, ReferenceItem> data) {
        return switch (kind) {
            case PETSTORE -> new PetstoreCache(data);
            case SYNCHRONIZED_TREE_MAP -> new SynchronizedTreeMap(data);
            case SKIP_LIST -> new SkipList(data);
        };
    }

    ReferenceItem get(String code);

    List<ReferenceItem> all();
}
