package ru.petstore.inventory.service;

import java.util.UUID;

/** One requested line of a reservation, as it arrives from {@code order-service}. */
public record ReserveLine(UUID productId, int quantity) {
}
