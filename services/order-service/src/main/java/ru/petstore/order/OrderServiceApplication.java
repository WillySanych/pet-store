package ru.petstore.order;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ru.petstore.order.config.OrderProperties;

@OpenAPIDefinition(info = @Info(title = "order-service API", version = "v1",
        description = "Оформление заказов: цены из каталога, резерв склада, события подтверждения и отмены в Kafka."),
        servers = @Server(url = "/"))
@SpringBootApplication
@EnableConfigurationProperties(OrderProperties.class)
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
