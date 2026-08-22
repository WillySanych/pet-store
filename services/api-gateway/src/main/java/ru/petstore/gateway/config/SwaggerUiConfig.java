package ru.petstore.gateway.config;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springdoc.core.properties.AbstractSwaggerUiConfigProperties.SwaggerUrl;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SwaggerUiConfig {

    private static final String DISPLAY_SUFFIX = "-service";

    public SwaggerUiConfig(SwaggerUiConfigProperties swaggerUi, GatewayProperties properties) {
        Set<SwaggerUrl> urls = new LinkedHashSet<>();
        for (String name : properties.getServices().keySet()) {
            String displayName = name + DISPLAY_SUFFIX;
            urls.add(new SwaggerUrl(displayName, GatewayRoutesConfig.apiDocsPath(name), displayName));
        }
        swaggerUi.setUrls(urls);
        properties.getServices().keySet().stream().findFirst()
                .ifPresent(name -> swaggerUi.setUrlsPrimaryName(name + DISPLAY_SUFFIX));
    }
}
