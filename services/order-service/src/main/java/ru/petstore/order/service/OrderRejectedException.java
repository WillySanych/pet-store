package ru.petstore.order.service;

public class OrderRejectedException extends RuntimeException {

    private final String code;

    public OrderRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
