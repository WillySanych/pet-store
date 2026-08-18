package ru.petstore.common.web;

public class ConcurrentChangeException extends RuntimeException {

    public ConcurrentChangeException(String message, Throwable cause) {
        super(message, cause);
    }
}
