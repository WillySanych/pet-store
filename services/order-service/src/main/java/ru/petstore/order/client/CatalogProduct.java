package ru.petstore.order.client;

import java.math.BigDecimal;
import java.util.UUID;

/** What the catalog tells the order about a product: the price to charge and whether it is on sale. */
public record CatalogProduct(UUID id, String name, BigDecimal price, boolean active) {
}
