package ru.petstore.inventory.service;

import java.util.UUID;

/**
 * A reservation exists for the order but its state does not allow the requested move.
 */
public class ReservationStateException extends RuntimeException {

    public ReservationStateException(UUID orderId, String status, String attempted) {
        super("Reservation for order " + orderId + " is " + status + " and cannot be " + attempted);
    }
}
