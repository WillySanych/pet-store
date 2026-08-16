package ru.petstore.catalog.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What {@code order-service} asks for over gRPC.
 */
public record ProductSummary(UUID id, String name, BigDecimal price, boolean active) {
}
