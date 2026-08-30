package ru.petstore.inventory;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ru.petstore.inventory.config.InventoryProperties;

@OpenAPIDefinition(info = @Info(title = "inventory-service API", version = "v1",
        description = "Остатки и резервы. Резервирование идёт по gRPC, списание — по событиям Kafka."),
        servers = @Server(url = "/"))
@SpringBootApplication
@EnableConfigurationProperties(InventoryProperties.class)
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
