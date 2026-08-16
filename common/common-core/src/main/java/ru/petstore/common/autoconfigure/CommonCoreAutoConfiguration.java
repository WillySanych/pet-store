package ru.petstore.common.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import ru.petstore.common.cache.CacheWarmupHealthIndicator;
import ru.petstore.common.cache.ReferenceCacheRegistry;
import ru.petstore.common.cache.RefreshableReferenceCache;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.common.overload.OverloadProtection;
import ru.petstore.common.web.GlobalExceptionHandler;
import ru.petstore.common.web.RequestMetricsFilter;
import ru.petstore.common.web.RequestTracingFilter;

/**
 * Wires the common components so a service needs one dependency on {@code common-core}.
 * {@link ConditionalOnMissingBean} on every bean lets a service replace any one of them
 * without switching off the rest.
 */
@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(CommonCoreProperties.class)
public class CommonCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ServiceMetrics serviceMetrics(MeterRegistry registry) {
        return new ServiceMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public RequestTracingFilter requestTracingFilter(CommonCoreProperties properties) {
        return new RequestTracingFilter(properties.getTracing().getHeaderName());
    }

    @Bean
    @ConditionalOnMissingBean
    public RequestMetricsFilter requestMetricsFilter(ServiceMetrics metrics) {
        return new RequestMetricsFilter(metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler(ServiceMetrics metrics) {
        return new GlobalExceptionHandler(metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public OverloadProtection overloadProtection(CommonCoreProperties properties, ServiceMetrics metrics) {
        return new OverloadProtection(properties.getOverload().getMaxConcurrent(), metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReferenceCacheRegistry referenceCacheRegistry(List<RefreshableReferenceCache<?, ?>> caches,
                                                         ServiceMetrics metrics) {
        return new ReferenceCacheRegistry(caches, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public CacheWarmupHealthIndicator cacheWarmupHealthIndicator(ReferenceCacheRegistry registry) {
        return new CacheWarmupHealthIndicator(registry);
    }
}
