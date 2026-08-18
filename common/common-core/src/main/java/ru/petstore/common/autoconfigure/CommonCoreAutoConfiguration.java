package ru.petstore.common.autoconfigure;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.petstore.common.cache.CacheWarmupHealthIndicator;
import ru.petstore.common.cache.ReferenceCacheRegistry;
import ru.petstore.common.cache.RefreshableReferenceCache;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.common.web.GlobalExceptionHandler;
import ru.petstore.common.web.OverloadInterceptor;
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
    @ConditionalOnMissingBean(name = "overloadBulkhead")
    public Bulkhead overloadBulkhead(CommonCoreProperties properties) {
        CommonCoreProperties.Overload overload = properties.getOverload();
        return Bulkhead.of("overload", BulkheadConfig.custom()
                .maxConcurrentCalls(overload.getMaxConcurrent())
                .maxWaitDuration(overload.getMaxWait())
                .build());
    }

    @Bean
    @ConditionalOnMissingBean
    public OverloadInterceptor overloadInterceptor(Bulkhead overloadBulkhead, ServiceMetrics metrics) {
        return new OverloadInterceptor(overloadBulkhead, metrics);
    }

    @Bean
    @ConditionalOnMissingBean(name = "overloadWebMvcConfigurer")
    public WebMvcConfigurer overloadWebMvcConfigurer(OverloadInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).excludePathPatterns("/actuator/**");
            }
        };
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
