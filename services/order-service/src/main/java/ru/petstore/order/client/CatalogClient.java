package ru.petstore.order.client;

import io.grpc.StatusRuntimeException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import ru.petstore.order.config.OrderProperties;
import ru.petstore.proto.catalog.CatalogServiceGrpc;
import ru.petstore.proto.catalog.GetProductsRequest;
import ru.petstore.proto.catalog.GetProductsResponse;
import ru.petstore.proto.catalog.Product;

/** Prices and the active flag over gRPC. Products are matched by id: the catalog deduplicates ids. */
@Component
public class CatalogClient {

    public static final String UPSTREAM = "catalog";

    private final CatalogServiceGrpc.CatalogServiceBlockingStub stub;
    private final UpstreamCall call;
    private final Duration deadline;

    public CatalogClient(CatalogServiceGrpc.CatalogServiceBlockingStub catalogStub,
                         UpstreamCall catalogCall,
                         OrderProperties properties) {
        this.stub = catalogStub;
        this.call = catalogCall;
        this.deadline = properties.getUpstreamDeadline();
    }

    public Map<UUID, CatalogProduct> products(Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }

        GetProductsRequest request = GetProductsRequest.newBuilder()
                .addAllProductIds(productIds.stream().distinct().map(UUID::toString).toList())
                .build();

        GetProductsResponse response = call.call(() -> {
            try {
                return stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                        .getProducts(request);
            } catch (StatusRuntimeException e) {
                throw GrpcErrors.translate(UPSTREAM, e);
            }
        });

        Map<UUID, CatalogProduct> byId = new LinkedHashMap<>();
        for (Product product : response.getProductsList()) {
            try {
                UUID id = UUID.fromString(product.getId());
                byId.put(id, new CatalogProduct(id, product.getName(),
                        new BigDecimal(product.getPrice()), product.getActive()));
            } catch (IllegalArgumentException e) {
                throw new UpstreamFailedException(UPSTREAM,
                        "unreadable product " + product.getId(), e);
            }
        }
        return byId;
    }
}
