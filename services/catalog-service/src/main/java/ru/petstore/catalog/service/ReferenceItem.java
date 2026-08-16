package ru.petstore.catalog.service;

/**
 * A reference entry as it is held in the cache.
 */
public record ReferenceItem(Long id, String code, String name) {
}
