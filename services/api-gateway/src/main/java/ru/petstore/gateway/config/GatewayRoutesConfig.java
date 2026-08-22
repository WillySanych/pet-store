package ru.petstore.gateway.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GatewayRoutesConfig {

    private static final Map<String, List<String>> PATHS = paths();

    private static final String API_DOCS_ROUTE_SUFFIX = "-api-docs";

    public static String apiDocsPath(String service) {
        return "/api-docs/" + service;
    }

    private static Map<String, List<String>> paths() {
        Map<String, List<String>> paths = new LinkedHashMap<>();
        paths.put("catalog", List.of("/api/v1/products/**",
                "/api/v1/categories/**",
                "/api/v1/species/**",
                "/api/v1/brands/**"));
        paths.put("inventory", List.of("/api/v1/stock/**",
                "/api/v1/warehouses/**",
                "/api/v1/reservation-statuses/**"));
        paths.put("customer", List.of("/api/v1/customers/**",
                "/api/v1/cities/**",
                "/api/v1/customer-statuses/**"));
        paths.put("order", List.of("/api/v1/orders/**",
                "/api/v1/order-statuses/**",
                "/api/v1/delivery-types/**",
                "/api/v1/payment-methods/**"));
        return Collections.unmodifiableMap(paths);
    }

    @Bean
    public RouteLocator petstoreRoutes(RouteLocatorBuilder builder, GatewayProperties properties) {
        RouteLocatorBuilder.Builder routes = builder.routes();

        PATHS.forEach((name, paths) -> routes.route(name, route -> route
                .path(paths.toArray(String[]::new))
                .uri(properties.service(name))));

        properties.getServices().forEach((name, uri) -> routes.route(name + API_DOCS_ROUTE_SUFFIX,
                route -> route.path(apiDocsPath(name))
                        .filters(filters -> filters.setPath("/v3/api-docs"))
                        .uri(uri)));

        return routes.build();
    }
}
