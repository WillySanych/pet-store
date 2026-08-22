package ru.petstore.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import ru.petstore.gateway.metrics.GatewayMetrics;
import ru.petstore.gateway.web.RateLimitFilter;
import ru.petstore.gateway.web.RequestMetricsFilter;
import ru.petstore.gateway.web.RequestTracingFilter;
import ru.petstore.gateway.web.SemaphoreRateLimiter;

@Configuration(proxyBeanMethods = false)
public class GatewayFiltersConfig {

    static final String TRACING_HEADER =
            "${petstore.tracing.header-name:" + RequestTracingFilter.REQUEST_ID_HEADER + "}";

    static final String METRICS_EXCLUDE_PREFIX = "${petstore.metrics.exclude-prefix:/actuator}";

    @Bean
    public GatewayMetrics gatewayMetrics(MeterRegistry meterRegistry) {
        return new GatewayMetrics(meterRegistry);
    }

    @Bean
    public RequestTracingFilter requestTracingFilter(@Value(TRACING_HEADER) String headerName) {
        return new RequestTracingFilter(headerName);
    }

    @Bean
    public RequestMetricsFilter requestMetricsFilter(GatewayMetrics metrics,
                                                     @Value(METRICS_EXCLUDE_PREFIX) String excludePrefix) {
        return new RequestMetricsFilter(metrics, excludePrefix);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "petstore.gateway.rate-limit", name = "enabled", matchIfMissing = true)
    @EnableScheduling
    static class RateLimitConfig {

        @Bean
        public SemaphoreRateLimiter semaphoreRateLimiter(GatewayProperties properties, GatewayMetrics metrics) {
            SemaphoreRateLimiter limiter =
                    new SemaphoreRateLimiter(properties.getRateLimit().getLimitForPeriod());
            metrics.bindRateLimiter(limiter);
            return limiter;
        }

        /** The period comes from the property the Retry-After of a rejection is built from: they must agree. */
        @Bean
        public SchedulingConfigurer rateLimitRefill(SemaphoreRateLimiter limiter, GatewayProperties properties) {
            return registrar -> registrar.addFixedRateTask(
                    limiter::refill, properties.getRateLimit().getRefreshPeriod());
        }

        @Bean
        public RateLimitFilter rateLimitFilter(SemaphoreRateLimiter limiter, GatewayMetrics metrics,
                                               ObjectMapper objectMapper, GatewayProperties properties,
                                               @Value(TRACING_HEADER) String headerName) {
            return new RateLimitFilter(limiter, metrics, objectMapper, headerName,
                    properties.getRateLimit().getRefreshPeriod().toSeconds());
        }
    }
}
