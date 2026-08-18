package ru.petstore.catalog.grpc;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import ru.petstore.catalog.service.ProductService;
import ru.petstore.catalog.service.ProductSummary;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.proto.catalog.CatalogServiceGrpc;
import ru.petstore.proto.catalog.GetProductsRequest;
import ru.petstore.proto.catalog.GetProductsResponse;
import ru.petstore.proto.catalog.Product;

/**
 * The gRPC half of the catalog: {@code order-service} asks for prices and the active flag while
 * placing an order.
 */
@GrpcService
public class CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase {

    /** The metric label, taken from the generated stub so it cannot drift from the proto. */
    private static final String ENDPOINT = CatalogServiceGrpc.getGetProductsMethod().getFullMethodName();

    private static final Logger log = LoggerFactory.getLogger(CatalogGrpcService.class);

    private final ProductService productService;
    private final Bulkhead overloadBulkhead;
    private final ServiceMetrics serviceMetrics;

    public CatalogGrpcService(ProductService productService, Bulkhead overloadBulkhead,
                              ServiceMetrics serviceMetrics) {
        this.productService = productService;
        this.overloadBulkhead = overloadBulkhead;
        this.serviceMetrics = serviceMetrics;
    }

    @Override
    public void getProducts(GetProductsRequest request, StreamObserver<GetProductsResponse> observer) {
        Set<UUID> ids = new LinkedHashSet<>(request.getProductIdsCount());
        for (String raw : request.getProductIdsList()) {
            try {
                ids.add(UUID.fromString(raw));
            } catch (IllegalArgumentException e) {
                observer.onError(Status.INVALID_ARGUMENT
                        .withDescription("Not a valid product id: " + raw)
                        .asRuntimeException());
                return;
            }
        }

        GetProductsResponse response;
        try {
            response = load(ids);
        } catch (StatusRuntimeException e) {
            observer.onError(e);
            return;
        }

        observer.onNext(response);
        observer.onCompleted();
    }

    private GetProductsResponse load(Set<UUID> ids) {
        try {
            return overloadBulkhead.executeSupplier(
                    () -> build(productService.getProductSummaries(List.copyOf(ids))));
        } catch (BulkheadFullException e) {
            serviceMetrics.recordOverloadRejected(ENDPOINT);
            log.warn("gRPC request rejected due to overload: {}", ENDPOINT);
            throw Status.RESOURCE_EXHAUSTED
                    .withDescription("Service is busy, retry later").asRuntimeException();
        } catch (DataAccessException e) {
            log.error("Catalog database unavailable while serving {}", ENDPOINT, e);
            throw Status.UNAVAILABLE
                    .withDescription("Catalog storage is unavailable").asRuntimeException();
        } catch (RuntimeException e) {
            log.error("Failed to serve {}", ENDPOINT, e);
            throw Status.INTERNAL
                    .withDescription("Internal catalog error").asRuntimeException();
        }
    }

    private static GetProductsResponse build(List<ProductSummary> summaries) {
        GetProductsResponse.Builder response = GetProductsResponse.newBuilder();
        for (ProductSummary product : summaries) {
            response.addProducts(Product.newBuilder()
                    .setId(product.id().toString())
                    .setName(product.name())
                    .setPrice(product.price().toPlainString())
                    .setActive(product.active())
                    .build());
        }
        return response.build();
    }
}
