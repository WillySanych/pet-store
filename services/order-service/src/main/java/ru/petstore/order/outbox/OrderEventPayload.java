package ru.petstore.order.outbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import ru.petstore.order.domain.CustomerOrder;

public record OrderEventPayload(
        String eventId,
        UUID orderId,
        String type,
        Instant occurredAt,
        UUID customerId,
        BigDecimal totalAmount) {

    public static final String ORDER_CONFIRMED = "ORDER_CONFIRMED";
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";

    public static OrderEventPayload of(CustomerOrder order, String type) {
        return new OrderEventPayload(UUID.randomUUID().toString(), order.getId(), type,
                Instant.now(), order.getCustomerId(), order.getTotalAmount());
    }
}
