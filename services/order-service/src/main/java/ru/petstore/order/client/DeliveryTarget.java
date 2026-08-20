package ru.petstore.order.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/** The answer of {@code GET /customers/{id}/delivery-target}: whom the order is for and where it goes. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeliveryTarget(Customer customer, Address address) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Customer(UUID id, String email, String firstName, String lastName, Reference status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Address(UUID id, Reference city, String street, String building, String apartment,
                          String postalCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reference(String code, String name) {
    }
}
