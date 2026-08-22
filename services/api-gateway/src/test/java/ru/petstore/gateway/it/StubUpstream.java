package ru.petstore.gateway.it;

import java.util.concurrent.ConcurrentLinkedDeque;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import ru.petstore.gateway.web.RequestTracingFilter;

final class StubUpstream {

    record Received(String method, String path, String requestId) {
    }

    private final DisposableServer server;

    private final ConcurrentLinkedDeque<Received> received = new ConcurrentLinkedDeque<>();

    private volatile int status = 200;
    private volatile String body = "{}";

    StubUpstream() {
        this.server = HttpServer.create()
                .port(0)
                .handle((request, response) -> {
                    String requestId = request.requestHeaders().get(RequestTracingFilter.REQUEST_ID_HEADER);
                    received.add(new Received(request.method().name(), request.fullPath(), requestId));
                    response.status(status).header("Content-Type", "application/json");
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
        return received.peekLast();
    }
}
