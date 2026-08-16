package ru.petstore.catalog.service;

import java.util.Locale;

/** The catalog reference tables; each one is backed by the cache bean it names here. */
public enum ReferenceType {

    CATEGORY,
    SPECIES,
    BRAND;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String cacheBeanName() {
        return code() + "Cache";
    }
}
