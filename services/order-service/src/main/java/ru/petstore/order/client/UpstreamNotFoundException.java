package ru.petstore.order.client;

/** An upstream does not know the requested entity; for the order it is a reason to refuse it. */
public class UpstreamNotFoundException extends RuntimeException {

    public UpstreamNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
