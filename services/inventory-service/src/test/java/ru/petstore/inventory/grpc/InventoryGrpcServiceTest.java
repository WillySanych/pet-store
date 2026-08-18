package ru.petstore.inventory.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.common.web.ServiceUnavailableException;
import ru.petstore.inventory.service.ConcurrentReservationException;
import ru.petstore.inventory.service.ReservationService;
import ru.petstore.inventory.service.ReservationStateException;
import ru.petstore.inventory.service.ReserveLine;
import ru.petstore.inventory.service.ReserveOutcome;
import ru.petstore.proto.inventory.ReleaseRequest;
import ru.petstore.proto.inventory.ReleaseResponse;
import ru.petstore.proto.inventory.ReserveItem;
import ru.petstore.proto.inventory.ReserveRequest;
import ru.petstore.proto.inventory.ReserveResponse;

@ExtendWith(MockitoExtension.class)
class InventoryGrpcServiceTest {

    @Mock
    private ReservationService reservationService;

    @Mock
    private StreamObserver<ReserveResponse> reserveObserver;

    @Mock
    private StreamObserver<ReleaseResponse> releaseObserver;

    private final UUID orderId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    private InventoryGrpcService service() {
        return service(bulkhead(64));
    }

    /** A bulkhead whose only permit is already taken, so the next call is rejected. */
    private InventoryGrpcService serviceWithFullBulkhead() {
        Bulkhead full = bulkhead(1);
        full.acquirePermission();
        return service(full);
    }

    private InventoryGrpcService service(Bulkhead overloadBulkhead) {
        return new InventoryGrpcService(reservationService, overloadBulkhead,
                new ServiceMetrics(new SimpleMeterRegistry()));
    }

    private static Bulkhead bulkhead(int maxConcurrent) {
        return Bulkhead.of("test", BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrent)
                .maxWaitDuration(Duration.ZERO)
                .build());
    }

    private ReserveRequest reserveRequest(String order, String product, int quantity) {
        return ReserveRequest.newBuilder()
                .setOrderId(order)
                .addItems(ReserveItem.newBuilder().setProductId(product).setQuantity(quantity))
                .build();
    }

    private ReserveRequest reserveRequest() {
        return reserveRequest(orderId.toString(), productId.toString(), 2);
    }

    private Status.Code reserveErrorCode() {
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(reserveObserver).onError(error.capture());
        assertThat(error.getValue()).isInstanceOf(StatusRuntimeException.class);
        return ((StatusRuntimeException) error.getValue()).getStatus().getCode();
    }

    private ReserveResponse capturedReserve() {
        ArgumentCaptor<ReserveResponse> response = ArgumentCaptor.forClass(ReserveResponse.class);
        verify(reserveObserver).onNext(response.capture());
        verify(reserveObserver).onCompleted();
        return response.getValue();
    }

    @Test
    @DisplayName("Удержанный резерв отдаётся как reserved без списка недоступных")
    void heldReservationIsReportedAsReserved() {
        when(reservationService.reserve(orderId, List.of(new ReserveLine(productId, 2))))
                .thenReturn(ReserveOutcome.held());

        service().reserve(reserveRequest(), reserveObserver);

        assertThat(capturedReserve().getReserved()).isTrue();
        assertThat(capturedReserve().getUnavailableProductIdsList()).isEmpty();
    }

    @Test
    @DisplayName("Нехватка отдаётся списком товаров, а не ошибкой")
    void shortageIsReportedAsUnavailableProducts() {
        when(reservationService.reserve(any(), any())).thenReturn(ReserveOutcome.refused(List.of(productId)));

        service().reserve(reserveRequest(), reserveObserver);

        assertThat(capturedReserve().getReserved()).isFalse();
        assertThat(capturedReserve().getUnavailableProductIdsList())
                .containsExactly(productId.toString());
        verify(reserveObserver, never()).onError(any());
    }

    @Test
    @DisplayName("Битый идентификатор заказа — INVALID_ARGUMENT до обращения к сервису")
    void malformedOrderIdIsRejected() {
        service().reserve(reserveRequest("not-a-uuid", productId.toString(), 1), reserveObserver);

        assertThat(reserveErrorCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(reservationService);
    }

    @Test
    @DisplayName("Битый идентификатор товара — INVALID_ARGUMENT до обращения к сервису")
    void malformedProductIdIsRejected() {
        service().reserve(reserveRequest(orderId.toString(), "not-a-uuid", 1), reserveObserver);

        assertThat(reserveErrorCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(reservationService);
    }

    @Test
    @DisplayName("Непроходная величина количества — INVALID_ARGUMENT из сервиса")
    void invalidQuantityIsReportedAsInvalidArgument() {
        when(reservationService.reserve(any(), any()))
                .thenThrow(new IllegalArgumentException("Quantity must be positive"));

        service().reserve(reserveRequest(orderId.toString(), productId.toString(), 0), reserveObserver);

        assertThat(reserveErrorCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    @DisplayName("Освобождённый резерв — FAILED_PRECONDITION, а не «товар кончился»")
    void reserveOnReleasedOrderIsFailedPrecondition() {
        when(reservationService.reserve(any(), any()))
                .thenThrow(new ReservationStateException(orderId, "RELEASED", "reserved again"));

        service().reserve(reserveRequest(), reserveObserver);

        assertThat(reserveErrorCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        verify(reserveObserver, never()).onNext(any());
    }

    @Test
    @DisplayName("Проигранная гонка — ABORTED: вызывающему есть смысл повторить")
    void lostRaceIsReportedAsAborted() {
        when(reservationService.reserve(any(), any()))
                .thenThrow(new ConcurrentReservationException(orderId, new RuntimeException("duplicate key")));

        service().reserve(reserveRequest(), reserveObserver);

        assertThat(reserveErrorCode()).isEqualTo(Status.Code.ABORTED);
    }

    @Test
    @DisplayName("Конфликт версий остатка — тоже ABORTED, а не UNAVAILABLE")
    void optimisticLockIsReportedAsAborted() {
        when(reservationService.reserve(any(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException("stock_item", productId));

        service().reserve(reserveRequest(), reserveObserver);

        assertThat(reserveErrorCode()).isEqualTo(Status.Code.ABORTED);
    }

    @Test
    @DisplayName("Недоступная БД — UNAVAILABLE, а не UNKNOWN из глубины драйвера")
    void databaseFailureIsReportedAsUnavailable() {
        when(reservationService.reserve(any(), any()))
                .thenThrow(new DataAccessResourceFailureException("pool exhausted"));

        service().reserve(reserveRequest(), reserveObserver);

        assertThat(reserveErrorCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    @DisplayName("Прочая ошибка сервиса — INTERNAL без утечки стектрейса наружу")
    void unexpectedFailureIsReportedAsInternal() {
        when(reservationService.reserve(any(), any())).thenThrow(new IllegalStateException("boom"));

        service().reserve(reserveRequest(), reserveObserver);

        assertThat(reserveErrorCode()).isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    @DisplayName("Непрогретый кеш справочников — UNAVAILABLE, а не INTERNAL")
    void coldReferenceCacheIsReportedAsUnavailable() {
        when(reservationService.reserve(any(), any()))
                .thenThrow(new ServiceUnavailableException("Reference data reservation_status is not loaded yet"));

        service().reserve(reserveRequest(), reserveObserver);

        assertThat(reserveErrorCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    @DisplayName("Исчерпанный bulkhead — RESOURCE_EXHAUSTED, аналог 429 по HTTP")
    void overloadIsReportedAsResourceExhausted() {
        serviceWithFullBulkhead().reserve(reserveRequest(), reserveObserver);

        assertThat(reserveErrorCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        verifyNoInteractions(reservationService);
    }

    @Test
    @DisplayName("Release проходит и при исчерпанном bulkhead: иначе резерв останется висеть")
    void releaseIsNotShedUnderLoad() {
        when(reservationService.release(orderId)).thenReturn(true);

        serviceWithFullBulkhead().release(ReleaseRequest.newBuilder().setOrderId(orderId.toString()).build(),
                releaseObserver);

        ArgumentCaptor<ReleaseResponse> response = ArgumentCaptor.forClass(ReleaseResponse.class);
        verify(releaseObserver).onNext(response.capture());
        verify(releaseObserver).onCompleted();
        assertThat(response.getValue().getReleased()).isTrue();
    }

    @Test
    @DisplayName("Отказ освобождения отдаётся как released=false, а не ошибкой")
    void refusedReleaseIsReportedInTheResponse() {
        when(reservationService.release(orderId)).thenReturn(false);

        service().release(ReleaseRequest.newBuilder().setOrderId(orderId.toString()).build(),
                releaseObserver);

        ArgumentCaptor<ReleaseResponse> response = ArgumentCaptor.forClass(ReleaseResponse.class);
        verify(releaseObserver).onNext(response.capture());
        assertThat(response.getValue().getReleased()).isFalse();
        verify(releaseObserver, never()).onError(any());
    }

    @Test
    @DisplayName("Битый идентификатор в Release — INVALID_ARGUMENT")
    void malformedOrderIdInReleaseIsRejected() {
        service().release(ReleaseRequest.newBuilder().setOrderId("not-a-uuid").build(), releaseObserver);

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(releaseObserver).onError(error.capture());
        assertThat(error.getValue()).isInstanceOfSatisfying(StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
        verifyNoInteractions(reservationService);
    }
}
