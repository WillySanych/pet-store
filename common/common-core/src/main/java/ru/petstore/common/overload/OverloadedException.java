package ru.petstore.common.overload;

public class OverloadedException extends RuntimeException {

    private final String endpoint;

    public OverloadedException(String endpoint) {
        super("Service overloaded, request to " + endpoint + " rejected");
        this.endpoint = endpoint;
    }

    public String endpoint() {
        return endpoint;
    }
}
