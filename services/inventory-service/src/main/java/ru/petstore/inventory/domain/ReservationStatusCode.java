package ru.petstore.inventory.domain;

/** The reservation states the code branches on. */
public enum ReservationStatusCode {

    /** Stock is held for the order. */
    ACTIVE,

    /** The order was confirmed. */
    COMMITTED,

    /** The order was cancelled: the hold is given back. */
    RELEASED,

    /** Nothing arrived in time; the sweeper gave the hold back. */
    EXPIRED;

    public String code() {
        return name();
    }
}
