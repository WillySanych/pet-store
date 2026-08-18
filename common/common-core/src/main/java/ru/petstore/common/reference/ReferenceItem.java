package ru.petstore.common.reference;

/** A reference entry as it is held in the cache. */
public record ReferenceItem(Long id, String code, String name) {
}
