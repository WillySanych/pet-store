package ru.petstore.order.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI orderOpenApi() {
        return new OpenAPI().info(new Info()
                .title("order-service API")
                .version("v1")
                .description("Оформление заказов: цены из каталога, резерв склада, "
                        + "события подтверждения и отмены в Kafka."));
    }
}
