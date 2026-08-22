package ru.petstore.gateway.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.CompositeReactiveHealthContributor;
import org.springframework.boot.actuate.health.ReactiveHealthContributor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import ru.petstore.gateway.health.DownstreamHealthIndicator;

@Configuration(proxyBeanMethods = false)
public class DownstreamHealthConfig {

    @Bean("downstream")
    public ReactiveHealthContributor downstreamHealth(GatewayProperties properties, WebClient.Builder builder) {
        WebClient webClient = builder.build();
        Map<String, ReactiveHealthContributor> contributors = new LinkedHashMap<>();
        properties.getServices().forEach((name, uri) -> contributors.put(
                name, new DownstreamHealthIndicator(webClient, uri, properties.getHealthTimeout())));
        return CompositeReactiveHealthContributor.fromMap(contributors);
    }
}
