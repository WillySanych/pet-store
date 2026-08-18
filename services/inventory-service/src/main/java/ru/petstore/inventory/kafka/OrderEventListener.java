package ru.petstore.inventory.kafka;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.inventory.service.ReservationService;

@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final ReservationService reservationService;
    private final ObjectMapper objectMapper;
    private final ServiceMetrics serviceMetrics;

    public OrderEventListener(ReservationService reservationService, ObjectMapper objectMapper,
                              ServiceMetrics serviceMetrics) {
        this.reservationService = reservationService;
        this.objectMapper = objectMapper;
        this.serviceMetrics = serviceMetrics;
    }

    @KafkaListener(topics = "${petstore.inventory.order-events-topic:order-events}")
    public void onMessage(String payload) {
        OrderEvent event;
        try {
            event = objectMapper.readValue(payload, OrderEvent.class);
        } catch (JacksonException e) {
            // Skipped, not rethrown: it will not parse next time either, and blocking the partition
            // on it stops every other order.
            serviceMetrics.recordError("malformed_order_event");
            log.error("Skipping unreadable order event: {}", payload, e);
            return;
        }

        if (event.orderId() == null || event.type() == null) {
            serviceMetrics.recordError("malformed_order_event");
            log.error("Skipping order event without an order id or a type: {}", payload);
            return;
        }

        boolean applied = switch (event.type()) {
            case OrderEvent.ORDER_CONFIRMED -> reservationService.commit(event.orderId());
            case OrderEvent.ORDER_CANCELLED -> reservationService.release(event.orderId());
            default -> {
                log.debug("Ignoring event {} for order {}", event.type(), event.orderId());
                yield true;
            }
        };
        if (!applied) {
            log.error("Event {} for order {} had no effect, see the error above",
                    event.eventId(), event.orderId());
        }
    }
}
