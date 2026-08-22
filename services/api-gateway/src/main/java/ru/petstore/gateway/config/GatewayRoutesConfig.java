package ru.petstore.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GatewayRoutesConfig {

    public static final String CATALOG = "catalog";
    public static final String INVENTORY = "inventory";
    public static final String CUSTOMER = "customer";
    public static final String ORDER = "order";

    private static final String API_DOCS_ROUTE_SUFFIX = "-api-docs";

    public static String apiDocsPath(String service) {
        return "/api-docs/" + service;
    }

    @Bean
    public RouteLocator petstoreRoutes(RouteLocatorBuilder builder, GatewayProperties properties) {
        RouteLocatorBuilder.Builder routes = builder.routes()
                .route(CATALOG, route -> route
                        .path("/api/v1/products/**",
                                "/api/v1/categories/**",
                                "/api/v1/species/**",
                                "/api/v1/brands/**")
                        .uri(properties.service(CATALOG)))
                .route(INVENTORY, route -> route
                        .path("/api/v1/stock/**",
                                "/api/v1/warehouses/**",
                                "/api/v1/reservation-statuses/**")
                        .uri(properties.service(INVENTORY)))
                .route(CUSTOMER, route -> route
                        .path("/api/v1/customers/**",
                                "/api/v1/cities/**",
                                "/api/v1/customer-statuses/**")
                        .uri(properties.service(CUSTOMER)))
                .route(ORDER, route -> route
                        .path("/api/v1/orders/**",
                                "/api/v1/order-statuses/**",
                                "/api/v1/delivery-types/**",
                                "/api/v1/payment-methods/**")
                        .uri(properties.service(ORDER)));

        properties.getServices().forEach((name, uri) -> routes.route(name + API_DOCS_ROUTE_SUFFIX,
                route -> route.path(apiDocsPath(name))
                        .filters(filters -> filters.setPath("/v3/api-docs"))
                        .uri(uri)));

        return routes.build();
    }
}
