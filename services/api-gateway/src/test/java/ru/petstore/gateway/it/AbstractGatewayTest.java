package ru.petstore.gateway.it;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

abstract class AbstractGatewayTest {

    static final Map<String, StubUpstream> UPSTREAMS = new LinkedHashMap<>();

    static {
        for (String service : List.of("catalog", "inventory", "customer", "order")) {
            UPSTREAMS.put(service, new StubUpstream());
        }
    }

    @DynamicPropertySource
    static void upstreamAddresses(DynamicPropertyRegistry registry) {
        UPSTREAMS.forEach((service, stub) ->
                registry.add("petstore.gateway.services." + service, stub::baseUrl));
    }

    @BeforeEach
    void resetUpstreams() {
        UPSTREAMS.values().forEach(StubUpstream::reset);
    }

    static StubUpstream upstream(String service) {
        return UPSTREAMS.get(service);
    }
}
