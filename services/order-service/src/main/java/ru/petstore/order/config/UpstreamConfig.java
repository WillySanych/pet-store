package ru.petstore.order.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.net.http.HttpClient;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.common.web.RequestTracingFilter;
import ru.petstore.order.client.CatalogClient;
import ru.petstore.order.client.CustomerClient;
import ru.petstore.order.client.InventoryClient;
import ru.petstore.order.client.UpstreamCall;
import ru.petstore.order.client.UpstreamExecutor;

/**
 * Everything the order needs to reach its upstreams: the virtual threads it calls them on, the
 * resilience instances configured in {@code application.yml}, and the REST client of customer-service.
 */
@Configuration
public class UpstreamConfig {

    @Bean(destroyMethod = "close")
    public UpstreamExecutor upstreamExecutor() {
        return new UpstreamExecutor();
    }

    @Bean
    public UpstreamCall catalogCall(RetryRegistry retries, CircuitBreakerRegistry breakers,
                                    TimeLimiterRegistry timeLimiters, UpstreamExecutor executor,
                                    ServiceMetrics metrics) {
        return upstreamCall(CatalogClient.UPSTREAM, retries, breakers, timeLimiters, executor, metrics);
    }

    @Bean
    public UpstreamCall inventoryCall(RetryRegistry retries, CircuitBreakerRegistry breakers,
                                      TimeLimiterRegistry timeLimiters, UpstreamExecutor executor,
                                      ServiceMetrics metrics) {
        return upstreamCall(InventoryClient.UPSTREAM, retries, breakers, timeLimiters, executor, metrics);
    }

    @Bean
    public UpstreamCall customerCall(RetryRegistry retries, CircuitBreakerRegistry breakers,
                                     TimeLimiterRegistry timeLimiters, UpstreamExecutor executor,
                                     ServiceMetrics metrics) {
        return upstreamCall(CustomerClient.UPSTREAM, retries, breakers, timeLimiters, executor, metrics);
    }

    @Bean
    public RateLimiter ordersRateLimiter(RateLimiterRegistry registry) {
        return registry.rateLimiter("orders");
    }

    @Bean
    public RestClient customerRestClient(OrderProperties properties) {
        return customerRestClientBuilder(properties).build();
    }

    static RestClient.Builder customerRestClientBuilder(OrderProperties properties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.getCustomerTimeout()).build());
        requestFactory.setReadTimeout(properties.getCustomerTimeout());

        return RestClient.builder()
                .baseUrl(properties.getCustomerServiceUrl())
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    String requestId = MDC.get(RequestTracingFilter.MDC_KEY);
                    if (requestId != null && !requestId.isBlank()) {
                        request.getHeaders().add(RequestTracingFilter.REQUEST_ID_HEADER, requestId);
                    }
                    return execution.execute(request, body);
                });
    }

    private static UpstreamCall upstreamCall(String name, RetryRegistry retries,
                                             CircuitBreakerRegistry breakers,
                                             TimeLimiterRegistry timeLimiters,
                                             UpstreamExecutor executor, ServiceMetrics metrics) {
        return new UpstreamCall(name, retries.retry(name), breakers.circuitBreaker(name),
                timeLimiters.timeLimiter(name), executor, metrics);
    }
}
