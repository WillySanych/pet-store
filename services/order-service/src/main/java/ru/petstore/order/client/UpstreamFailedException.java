package ru.petstore.order.client;

/** An upstream answered with an error that repeating will not fix. */
public class UpstreamFailedException extends RuntimeException {

    private final String upstream;

    public UpstreamFailedException(String upstream, String reason, Throwable cause) {
        super(upstream + " refused the call: " + reason, cause);
        this.upstream = upstream;
    }

    public String getUpstream() {
        return upstream;
    }
}
