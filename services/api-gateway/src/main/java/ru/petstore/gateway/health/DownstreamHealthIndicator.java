package ru.petstore.gateway.health;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

public class DownstreamHealthIndicator implements ReactiveHealthIndicator {

    private static final String HEALTH_PATH = "/actuator/health";

    private final WebClient webClient;
    private final URI baseUri;
    private final URI healthUri;
    private final Duration timeout;

    public DownstreamHealthIndicator(WebClient webClient, URI baseUri, Duration timeout) {
        this.webClient = webClient;
        this.baseUri = baseUri;
        this.healthUri = UriComponentsBuilder.fromUri(baseUri).path(HEALTH_PATH).build().toUri();
        this.timeout = timeout;
    }

    @Override
    public Mono<Health> health() {
        return webClient.get()
                .uri(healthUri)
                .retrieve()
                .toBodilessEntity()
                .timeout(timeout)
                .map(response -> Health.up().withDetail("url", baseUri.toString()).build())
                .onErrorResume(error -> Mono.just(Health.down()
                        .withDetail("url", baseUri.toString())
                        .withDetail("error", error.getMessage())
                        .build()));
    }
}
