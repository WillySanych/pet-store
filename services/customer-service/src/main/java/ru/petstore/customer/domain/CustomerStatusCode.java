package ru.petstore.customer.domain;

/** The customer states the code branches on. */
public enum CustomerStatusCode {

    /** Registered, has not ordered anything yet. */
    NEW,

    /** Orders are accepted. */
    ACTIVE,

    /** Orders are refused; order-service reads this from the delivery target. */
    BLOCKED;

    public String code() {
        return name();
    }
}
