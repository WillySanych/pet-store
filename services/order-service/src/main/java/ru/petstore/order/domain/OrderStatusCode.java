package ru.petstore.order.domain;

public enum OrderStatusCode {

    NEW,
    CONFIRMED,
    CANCELLED;

    public String code() {
        return name();
    }
}
