package ru.petstore.common.reference;

import java.util.Locale;

/**
 * One reference table of a service. Implemented by an enum per service.
 */
public interface ReferenceKind {

    String name();

    /** The code used in messages and paths: {@code RESERVATION_STATUS} → {@code reservation_status}. */
    default String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** {@code RESERVATION_STATUS} → {@code reservationStatusCache}. */
    default String cacheBeanName() {
        StringBuilder bean = new StringBuilder();
        for (String word : name().toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            bean.append(bean.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return bean.append("Cache").toString();
    }
}
