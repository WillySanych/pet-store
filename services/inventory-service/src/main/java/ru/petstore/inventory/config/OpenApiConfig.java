package ru.petstore.inventory.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI inventoryOpenApi() {
        return new OpenAPI().info(new Info()
                .title("inventory-service API")
                .version("v1")
                .description("Остатки и резервы. Резервирование идёт по gRPC, списание — по событиям Kafka."));
    }
}
