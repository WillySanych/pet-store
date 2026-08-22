package ru.petstore.gateway.it;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import ru.petstore.gateway.web.RequestTracingFilter;

final class StubUpstream {

    record Received(String method, String path, String requestId) {
    }

    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(5);

    private final DisposableServer server;
    private final BlockingQueue<Received> received = new LinkedBlockingQueue<>();

    private volatile int status = 200;
    private volatile String body = "{}";

    StubUpstream() {
        this.server = HttpServer.create()
                .port(0)
                .handle((request, response) -> {
                    received.add(new Received(
                            request.method().name(),
                            request.fullPath(),
                            request.requestHeaders().get(RequestTracingFilter.REQUEST_ID_HEADER)));
                    response.status(status).header("Content-Type", "application/json");
                    String requestId = request.requestHeaders().get(RequestTracingFilter.REQUEST_ID_HEADER);
                    if (requestId != null) {
                        response.header(RequestTracingFilter.REQUEST_ID_HEADER, requestId);
                    }
                    return response.sendString(Mono.just(body));
                })
                .bindNow();
    }

    String baseUrl() {
        return "http://localhost:" + server.port();
    }

    void answerWith(int status, String body) {
        this.status = status;
        this.body = body;
    }

    void reset() {
        received.clear();
        answerWith(200, "{}");
    }

    int requestCount() {
        return received.size();
    }

    Received lastRequest() {
        try {
            return received.poll(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a proxied request", e);
        }
    }
}
