package ru.petstore.catalog.web.dto;

public record ProductFilterRequest(
        String category,
        String species,
        String brand,
        Boolean active) {
}
