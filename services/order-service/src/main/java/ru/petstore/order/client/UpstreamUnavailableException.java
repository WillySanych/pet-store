package ru.petstore.order.client;

/**
 * An upstream did not answer: timeout, transport failure, overload or an open circuit breaker.
 * Retryable — the name is listed in {@code resilience4j.*.retry-exceptions}.
 */
public class UpstreamUnavailableException extends RuntimeException {

    private final String upstream;

    public UpstreamUnavailableException(String upstream, String reason, Throwable cause) {
        super(upstream + " is unavailable: " + reason, cause);
        this.upstream = upstream;
    }

    public String getUpstream() {
        return upstream;
    }
}
