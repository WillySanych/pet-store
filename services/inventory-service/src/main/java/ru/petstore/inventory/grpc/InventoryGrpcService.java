package ru.petstore.inventory.grpc;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.common.web.ServiceUnavailableException;
import ru.petstore.inventory.service.ConcurrentReservationException;
import ru.petstore.inventory.service.ReservationService;
import ru.petstore.inventory.service.ReservationStateException;
import ru.petstore.inventory.service.ReserveLine;
import ru.petstore.inventory.service.ReserveOutcome;
import ru.petstore.proto.inventory.InventoryServiceGrpc;
import ru.petstore.proto.inventory.ReleaseRequest;
import ru.petstore.proto.inventory.ReleaseResponse;
import ru.petstore.proto.inventory.ReserveItem;
import ru.petstore.proto.inventory.ReserveRequest;
import ru.petstore.proto.inventory.ReserveResponse;

/**
 * The gRPC half of the inventory: {@code order-service} holds stock while placing an order and
 * gives it back when the saga rolls back.
 */
@GrpcService
public class InventoryGrpcService extends InventoryServiceGrpc.InventoryServiceImplBase {

    /** Metric labels, taken from the generated stub so they cannot drift from the proto. */
    private static final String RESERVE_ENDPOINT =
            InventoryServiceGrpc.getReserveMethod().getFullMethodName();
    private static final String RELEASE_ENDPOINT =
            InventoryServiceGrpc.getReleaseMethod().getFullMethodName();

    private static final Logger log = LoggerFactory.getLogger(InventoryGrpcService.class);

    private final ReservationService reservationService;
    private final Bulkhead overloadBulkhead;
    private final ServiceMetrics serviceMetrics;

    public InventoryGrpcService(ReservationService reservationService, Bulkhead overloadBulkhead,
                                ServiceMetrics serviceMetrics) {
        this.reservationService = reservationService;
        this.overloadBulkhead = overloadBulkhead;
        this.serviceMetrics = serviceMetrics;
    }

    @Override
    public void reserve(ReserveRequest request, StreamObserver<ReserveResponse> observer) {
        UUID orderId;
        List<ReserveLine> lines = new ArrayList<>(request.getItemsCount());
        try {
            orderId = orderId(request.getOrderId());
            for (ReserveItem item : request.getItemsList()) {
                lines.add(new ReserveLine(productId(item.getProductId()), item.getQuantity()));
            }
        } catch (IllegalArgumentException e) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        }

        ReserveResponse response;
        try {
            ReserveOutcome outcome = guarded(RESERVE_ENDPOINT, () -> overloadBulkhead.executeSupplier(
                    () -> reservationService.reserve(orderId, lines)));
            ReserveResponse.Builder builder = ReserveResponse.newBuilder().setReserved(outcome.reserved());
            outcome.unavailableProductIds().forEach(id -> builder.addUnavailableProductIds(id.toString()));
            response = builder.build();
        } catch (StatusRuntimeException e) {
            observer.onError(e);
            return;
        }

        observer.onNext(response);
        observer.onCompleted();
    }

    @Override
    public void release(ReleaseRequest request, StreamObserver<ReleaseResponse> observer) {
        UUID orderId;
        try {
            orderId = orderId(request.getOrderId());
        } catch (IllegalArgumentException e) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        }

        boolean released;
        try {
            released = guarded(RELEASE_ENDPOINT, () -> reservationService.release(orderId));
        } catch (StatusRuntimeException e) {
            observer.onError(e);
            return;
        }

        observer.onNext(ReleaseResponse.newBuilder().setReleased(released).build());
        observer.onCompleted();
    }

    private <T> T guarded(String endpoint, Supplier<T> action) {
        try {
            return action.get();
        } catch (BulkheadFullException e) {
            serviceMetrics.recordOverloadRejected(endpoint);
            log.warn("gRPC request rejected due to overload: {}", endpoint);
            throw Status.RESOURCE_EXHAUSTED.withDescription("Service is busy, retry later").asRuntimeException();
        } catch (IllegalArgumentException e) {
            throw Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException();
        } catch (ReservationStateException e) {
            log.warn("{} refused: {}", endpoint, e.getMessage());
            throw Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException();
        } catch (ConcurrentReservationException | OptimisticLockingFailureException e) {
            log.warn("{} lost a race, asking the caller to retry: {}", endpoint, e.getMessage());
            throw Status.ABORTED.withDescription("Concurrent change, retry the request").asRuntimeException();
        } catch (ServiceUnavailableException e) {
            log.warn("{} rejected, service not ready: {}", endpoint, e.getMessage());
            throw Status.UNAVAILABLE.withDescription(e.getMessage()).asRuntimeException();
        } catch (DataAccessException e) {
            log.error("Inventory database unavailable while serving {}", endpoint, e);
            throw Status.UNAVAILABLE.withDescription("Inventory storage is unavailable").asRuntimeException();
        } catch (RuntimeException e) {
            log.error("Failed to serve {}", endpoint, e);
            throw Status.INTERNAL.withDescription("Internal inventory error").asRuntimeException();
        }
    }

    private static UUID orderId(String raw) {
        return parse(raw, "order id");
    }

    private static UUID productId(String raw) {
        return parse(raw, "product id");
    }

    private static UUID parse(String raw, String what) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a valid " + what + ": " + raw);
        }
    }
}
