package ru.petstore.order.config;

import java.util.List;
import net.devh.boot.grpc.client.channelfactory.GrpcChannelFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.petstore.common.grpc.RequestIdClientInterceptor;
import ru.petstore.proto.catalog.CatalogServiceGrpc;
import ru.petstore.proto.inventory.InventoryServiceGrpc;

@Configuration
public class GrpcClientConfig {

    @Bean
    public RequestIdClientInterceptor requestIdClientInterceptor() {
        return new RequestIdClientInterceptor();
    }

    @Bean
    public CatalogServiceGrpc.CatalogServiceBlockingStub catalogStub(
            GrpcChannelFactory channels, RequestIdClientInterceptor tracing) {
        return CatalogServiceGrpc.newBlockingStub(channels.createChannel("catalog", List.of(tracing)));
    }

    @Bean
    public InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub(
            GrpcChannelFactory channels, RequestIdClientInterceptor tracing) {
        return InventoryServiceGrpc.newBlockingStub(channels.createChannel("inventory", List.of(tracing)));
    }
}
