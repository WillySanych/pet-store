package ru.petstore.common.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.petstore.common.cache.CacheWarmupHealthIndicator;
import ru.petstore.common.cache.ReferenceCacheRegistry;
import ru.petstore.common.cache.RefreshableReferenceCache;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.common.web.GlobalExceptionHandler;
import ru.petstore.common.web.OverloadInterceptor;
import ru.petstore.common.web.RequestMetricsFilter;
import ru.petstore.common.web.RequestTracingFilter;

class CommonCoreAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonCoreAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    @DisplayName("Все общие компоненты регистрируются без дополнительной настройки")
    void allCommonBeansAreRegisteredOutOfTheBox() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(ServiceMetrics.class)
                .hasSingleBean(RequestTracingFilter.class)
                .hasSingleBean(RequestMetricsFilter.class)
                .hasSingleBean(GlobalExceptionHandler.class)
                .hasSingleBean(Bulkhead.class)
                .hasSingleBean(OverloadInterceptor.class)
                .hasSingleBean(ReferenceCacheRegistry.class)
                .hasSingleBean(CacheWarmupHealthIndicator.class));
    }

    @Test
    @DisplayName("Предел перегрузки берётся из свойств")
    void overloadLimitIsTakenFromProperties() {
        runner.withPropertyValues("petstore.overload.max-concurrent=3")
                .run(context -> assertThat(context.getBean(Bulkhead.class)
                        .getMetrics().getAvailableConcurrentCalls()).isEqualTo(3));
    }

    @Test
    @DisplayName("Кеши сервиса подхватываются реестром и привязываются к метрикам")
    void serviceCachesArePickedUpAndBoundToMetrics() {
        runner.withUserConfiguration(CacheConfig.class).run(context -> {
            var registry = context.getBean(ReferenceCacheRegistry.class);
            assertThat(registry.caches()).hasSize(1);

            registry.refreshAll();

            var meterRegistry = context.getBean(MeterRegistry.class);
            assertThat(meterRegistry.get(ServiceMetrics.CACHE_SIZE).tag("cache", "categories")
                    .gauge().value()).isEqualTo(2);
            assertThat(registry.allWarmedUp()).isTrue();
        });
    }

    @Test
    @DisplayName("Сервис может подменить любой бин своим")
    void serviceCanOverrideAnyBean() {
        runner.withUserConfiguration(CustomOverloadConfig.class)
                .run(context -> assertThat(context.getBean(Bulkhead.class)
                        .getMetrics().getAvailableConcurrentCalls()).isEqualTo(99));
    }

    @Configuration
    static class CacheConfig {
        @Bean
        RefreshableReferenceCache<String, String> categories() {
            return new RefreshableReferenceCache<>("categories", () -> Map.of("a", "Alpha", "b", "Bravo"));
        }
    }

    @Configuration
    static class CustomOverloadConfig {
        @Bean
        Bulkhead overloadBulkhead() {
            return Bulkhead.of("custom", BulkheadConfig.custom().maxConcurrentCalls(99).build());
        }
    }
}
