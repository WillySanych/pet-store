package ru.petstore.benchmarks;

import java.util.LinkedHashMap;
import java.util.Map;
import ru.petstore.common.reference.ReferenceItem;

final class ReferenceData {

    private ReferenceData() {
    }

    static Map<String, ReferenceItem> sample(int size) {
        Map<String, ReferenceItem> data = new LinkedHashMap<>(size);
        for (int i = 0; i < size; i++) {
            String code = String.format("CODE_%04d", i);
            data.put(code, new ReferenceItem((long) i, code, "Reference entry " + i));
        }
        return data;
    }
}
