package ru.petstore.common.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Defaults shared by every service, added as the property source of the lowest priority: the
 * {@code application.yml} of a service still wins.
 */
public class CommonDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new LinkedHashMap<>();

        defaults.put("management.endpoint.health.probes.enabled", "true");
        defaults.put("management.endpoint.health.group.readiness.include", "readinessState,cacheWarmup");

        defaults.put("management.endpoints.web.exposure.include", "health,info,prometheus");
        defaults.put("management.endpoint.health.show-details", "always");
        defaults.put("management.metrics.tags.service", "${spring.application.name:unknown}");

        defaults.put("logging.structured.format.console", "ecs");

        defaults.put("spring.jpa.open-in-view", "false");

        defaults.put("spring.data.web.pageable.max-page-size", "100");

        defaults.put("springdoc.swagger-ui.path", "/swagger-ui.html");
        defaults.put("springdoc.swagger-ui.operations-sorter", "method");

        environment.getPropertySources()
                .addLast(new MapPropertySource("petstore-common-defaults", defaults));
    }
}
