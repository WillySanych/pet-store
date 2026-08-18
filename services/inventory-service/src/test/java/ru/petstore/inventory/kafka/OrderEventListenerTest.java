package ru.petstore.inventory.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.inventory.service.ReservationService;

@ExtendWith(MockitoExtension.class)
class OrderEventListenerTest {

    @Mock
    private ReservationService reservationService;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final UUID orderId = UUID.randomUUID();

    /** Modules discovered as Boot discovers them — {@code occurredAt} needs the time one. */
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private OrderEventListener listener() {
        return new OrderEventListener(reservationService, MAPPER, new ServiceMetrics(registry));
    }

    private String event(String type) {
        return """
                {"eventId":"%s","orderId":"%s","type":"%s","occurredAt":"2026-08-16T12:00:00Z"}"""
                .formatted(UUID.randomUUID(), orderId, type);
    }

    private double errorsOfType(String type) {
        var counter = registry.find(ServiceMetrics.ERRORS).tag("type", type).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("ORDER_CONFIRMED списывает остаток")
    void confirmedEventCommitsTheReservation() {
        listener().onMessage(event(OrderEvent.ORDER_CONFIRMED));

        verify(reservationService).commit(orderId);
    }

    @Test
    @DisplayName("ORDER_CANCELLED освобождает резерв")
    void cancelledEventReleasesTheReservation() {
        listener().onMessage(event(OrderEvent.ORDER_CANCELLED));

        verify(reservationService).release(orderId);
    }

    @Test
    @DisplayName("Незнакомый тип события пропускается молча")
    void unknownEventTypeIsIgnored() {
        listener().onMessage(event("ORDER_CREATED"));

        verifyNoInteractions(reservationService);
    }

    @Test
    @DisplayName("Лишние поля продюсера не ломают потребителя")
    void unknownFieldsAreTolerated() {
        listener().onMessage("""
                {"eventId":"e-1","orderId":"%s","type":"ORDER_CONFIRMED",
                 "occurredAt":"2026-08-16T12:00:00Z","customerId":"c-1","items":[{"sku":"X"}]}"""
                .formatted(orderId));

        verify(reservationService).commit(orderId);
    }

    @Test
    @DisplayName("Нечитаемое сообщение пропускается, а не блокирует партицию навсегда")
    void malformedMessageIsSkipped() {
        listener().onMessage("{not json");

        verifyNoInteractions(reservationService);
        assertThat(errorsOfType("malformed_order_event")).isEqualTo(1);
    }

    @Test
    @DisplayName("Событие без заказа или типа пропускается")
    void eventWithoutOrderIdOrTypeIsSkipped() {
        listener().onMessage("""
                {"eventId":"e-1","type":"ORDER_CONFIRMED"}""");
        listener().onMessage("""
                {"eventId":"e-2","orderId":"%s"}""".formatted(orderId));

        verifyNoInteractions(reservationService);
        assertThat(errorsOfType("malformed_order_event")).isEqualTo(2);
    }

    @Test
    @DisplayName("Упавшая БД пробрасывается наверх: такое сообщение надо повторить, а не пропустить")
    void storageFailurePropagatesToTheContainer() {
        when(reservationService.commit(any())).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> listener().onMessage(event(OrderEvent.ORDER_CONFIRMED)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
