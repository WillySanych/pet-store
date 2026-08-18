package ru.petstore.inventory.service;

import java.util.UUID;

public class ConcurrentReservationException extends RuntimeException {

    public ConcurrentReservationException(UUID orderId, Throwable cause) {
        super("Concurrent change while reserving order " + orderId, cause);
    }
}
