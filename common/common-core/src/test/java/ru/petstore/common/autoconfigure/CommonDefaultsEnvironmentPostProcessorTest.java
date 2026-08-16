package ru.petstore.common.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class CommonDefaultsEnvironmentPostProcessorTest {

    private final CommonDefaultsEnvironmentPostProcessor processor =
            new CommonDefaultsEnvironmentPostProcessor();

    private StandardEnvironment processed(Map<String, Object> serviceProperties) {
        var environment = new StandardEnvironment();
        if (!serviceProperties.isEmpty()) {
            environment.getPropertySources()
                    .addFirst(new MapPropertySource("service", serviceProperties));
        }
        processor.postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }

    @Test
    @DisplayName("Сервис получает общие умолчания, ничего у себя не объявляя")
    void serviceInheritsSharedDefaults() {
        var environment = processed(Map.of());

        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .contains("prometheus");
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,cacheWarmup");
        assertThat(environment.getProperty("spring.data.web.pageable.max-page-size")).isEqualTo("100");
    }

    @Test
    @DisplayName("Значение сервиса перебивает умолчание, а не наоборот")
    void serviceValueWinsOverDefault() {
        var environment = processed(Map.of(
                "spring.jpa.open-in-view", "true",
                "management.endpoints.web.exposure.include", "health"));

        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("true");
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health");
    }

    @Test
    @DisplayName("ddl-auto не назначается общим умолчанием: явное значение сломало бы срезовые тесты")
    void ddlAutoIsLeftToTheService() {
        assertThat(processed(Map.of()).getProperty("spring.jpa.hibernate.ddl-auto")).isNull();
    }

    @Test
    @DisplayName("Безымянное приложение не роняет контекст на нерезолвимом плейсхолдере")
    void unnamedApplicationStillResolvesTheServiceTag() {
        assertThat(processed(Map.of()).getProperty("management.metrics.tags.service"))
                .isEqualTo("unknown");
    }
}
