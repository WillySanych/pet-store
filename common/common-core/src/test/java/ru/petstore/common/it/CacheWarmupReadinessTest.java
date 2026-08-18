package ru.petstore.common.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

/** The warm-up indicator must land in the readiness group, otherwise a cold pod gets traffic. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.endpoints.web.exposure.include=health",
                "management.endpoint.health.show-details=always",
                "management.endpoint.health.probes.enabled=true"
        })
class CacheWarmupReadinessTest {

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Test
    @DisplayName("Индикатор прогрева виден в общем health")
    void warmupIndicatorIsVisibleInOverallHealth() {
        String body = testRestTemplate.getForObject("/actuator/health", String.class);

        assertThat(body).contains("cacheWarmup");
    }

    @Test
    @DisplayName("Индикатор прогрева входит в группу readiness")
    void warmupIndicatorIsPartOfReadinessGroup() {
        String body = testRestTemplate.getForObject("/actuator/health/readiness", String.class);

        // If this fails, the group holds only readinessState and warm-up does not gate readiness
        assertThat(body).contains("cacheWarmup");
    }
}
