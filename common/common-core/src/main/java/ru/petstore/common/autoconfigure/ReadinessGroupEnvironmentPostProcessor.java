package ru.petstore.common.autoconfigure;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Adds the cache warm-up indicator to the readiness group: by default the group holds only
 * {@code readinessState}, so Kubernetes would send traffic to a pod with a cold cache.
 *
 * <p>Probes are enabled along with the group membership — without them {@code readinessState}
 * does not exist, and a reference to a missing indicator fails the context on startup.
 */
public class ReadinessGroupEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String GROUP = "management.endpoint.health.group.readiness.include";
    private static final String PROBES = "management.endpoint.health.probes.enabled";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(
                "petstore-common-defaults",
                Map.of(
                        PROBES, "true",
                        GROUP, "readinessState,cacheWarmup")));
    }
}
