package ru.petstore.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "petstore.gateway")
@Validated
public class GatewayProperties {

    private final Map<String, URI> services = new LinkedHashMap<>();

    @DurationMin(millis = 1)
    private Duration healthTimeout = Duration.ofSeconds(1);

    @Valid
    private final RateLimit rateLimit = new RateLimit();

    public Map<String, URI> getServices() {
        return services;
    }

    public URI service(String name) {
        URI uri = services.get(name);
        if (uri == null) {
            throw new IllegalStateException("petstore.gateway.services." + name + " is not configured");
        }
        return uri;
    }

    public Duration getHealthTimeout() {
        return healthTimeout;
    }

    public void setHealthTimeout(Duration healthTimeout) {
        this.healthTimeout = healthTimeout;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public static class RateLimit {

        @Min(1)
        private int limitForPeriod = 500;

        @DurationMin(millis = 1)
        private Duration refreshPeriod = Duration.ofSeconds(1);

        public int getLimitForPeriod() {
            return limitForPeriod;
        }

        public void setLimitForPeriod(int limitForPeriod) {
            this.limitForPeriod = limitForPeriod;
        }

        public Duration getRefreshPeriod() {
            return refreshPeriod;
        }

        public void setRefreshPeriod(Duration refreshPeriod) {
            this.refreshPeriod = refreshPeriod;
        }
    }
}
