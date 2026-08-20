package ru.petstore.order.client;

import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.order.config.OrderProperties;
import ru.petstore.proto.inventory.InventoryServiceGrpc;
import ru.petstore.proto.inventory.ReleaseRequest;
import ru.petstore.proto.inventory.ReserveItem;
import ru.petstore.proto.inventory.ReserveRequest;
import ru.petstore.proto.inventory.ReserveResponse;

@Component
public class InventoryClient {

    public static final String UPSTREAM = "inventory";

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    private final InventoryServiceGrpc.InventoryServiceBlockingStub stub;
    private final UpstreamCall call;
    private final ServiceMetrics serviceMetrics;
    private final Duration deadline;

    public InventoryClient(InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub,
                           UpstreamCall inventoryCall,
                           ServiceMetrics serviceMetrics,
                           OrderProperties properties) {
        this.stub = inventoryStub;
        this.call = inventoryCall;
        this.serviceMetrics = serviceMetrics;
        this.deadline = properties.getUpstreamDeadline();
    }

    public ReserveResult reserve(UUID orderId, Map<UUID, Integer> lines) {
        ReserveRequest.Builder request = ReserveRequest.newBuilder().setOrderId(orderId.toString());
        lines.forEach((productId, quantity) -> request.addItems(ReserveItem.newBuilder()
                .setProductId(productId.toString())
                .setQuantity(quantity)
                .build()));

        ReserveResponse response = call.call(() -> {
            try {
                return stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                        .reserve(request.build());
            } catch (StatusRuntimeException e) {
                throw GrpcErrors.translate(UPSTREAM, e);
            }
        });

        if (response.getReserved()) {
            return ReserveResult.held();
        }
        List<UUID> unavailable = new ArrayList<>(response.getUnavailableProductIdsCount());
        for (String id : response.getUnavailableProductIdsList()) {
            try {
                unavailable.add(UUID.fromString(id));
            } catch (IllegalArgumentException e) {
                throw new UpstreamFailedException(UPSTREAM, "unreadable product id " + id, e);
            }
        }
        return ReserveResult.refused(unavailable);
    }

    public boolean release(UUID orderId) {
        return call.call(() -> {
            try {
                return stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                        .release(ReleaseRequest.newBuilder().setOrderId(orderId.toString()).build())
                        .getReleased();
            } catch (StatusRuntimeException e) {
                throw GrpcErrors.translate(UPSTREAM, e);
            }
        });
    }

    public void releaseQuietly(UUID orderId) {
        try {
            if (!release(orderId)) {
                serviceMetrics.recordError("compensation_refused");
                log.error("Inventory refused to release the hold of order {}", orderId);
            }
        } catch (RuntimeException e) {
            serviceMetrics.recordError("compensation_failed");
            log.error("Failed to release the hold of order {}; it expires on its own", orderId, e);
        }
    }
}
