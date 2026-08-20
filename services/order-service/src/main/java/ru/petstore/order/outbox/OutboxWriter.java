package ru.petstore.order.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import ru.petstore.common.web.RequestTracingFilter;
import ru.petstore.order.config.OrderProperties;
import ru.petstore.order.domain.CustomerOrder;
import ru.petstore.order.domain.OutboxMessage;
import ru.petstore.order.repository.OutboxRepository;

@Component
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final OrderProperties properties;

    public OutboxWriter(OutboxRepository outboxRepository, ObjectMapper objectMapper,
                        OrderProperties properties) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public OutboxMessage append(CustomerOrder order, String type) {
        OrderEventPayload payload = OrderEventPayload.of(order, type);
        return outboxRepository.save(OutboxMessage.of(
                order.getId(),
                properties.getOrderEventsTopic(),
                type,
                serialize(payload),
                MDC.get(RequestTracingFilter.MDC_KEY)));
    }

    private String serialize(OrderEventPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + payload.type()
                    + " of order " + payload.orderId(), e);
        }
    }
}
