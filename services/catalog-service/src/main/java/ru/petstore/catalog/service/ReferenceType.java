package ru.petstore.catalog.service;

import ru.petstore.common.reference.ReferenceKind;

/** The catalog reference tables; each one is backed by the cache bean it names here. */
public enum ReferenceType implements ReferenceKind {

    CATEGORY,
    SPECIES,
    BRAND
}
