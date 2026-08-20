package ru.petstore.order.service;

import java.util.UUID;

public class OrderStateException extends RuntimeException {

    public OrderStateException(UUID orderId, String status, String attempted) {
        super("Order " + orderId + " is " + status + " and cannot be " + attempted);
    }
}
