package ru.petstore.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.common.web.RequestTracingFilter;
import ru.petstore.order.config.OrderProperties;
import ru.petstore.order.domain.OutboxMessage;
import ru.petstore.order.repository.OutboxRepository;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private SimpleMeterRegistry registry;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        publisher = new OutboxPublisher(outboxRepository, kafkaTemplate,
                new ServiceMetrics(registry), new OrderProperties());
    }

    @Test
    @DisplayName("Сообщение уходит в Kafka ключом заказа и помечается опубликованным")
    void messageIsSentAndMarked() {
        OutboxMessage message = message("trace-1");
        pending(message);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(sent());

        assertThat(publisher.publishBatch()).isEqualTo(1);

        ArgumentCaptor<ProducerRecord<String, String>> record = recordCaptor();
        verify(kafkaTemplate).send(record.capture());
        assertThat(record.getValue().topic()).isEqualTo("order-events");
        assertThat(record.getValue().key()).isEqualTo(message.getAggregateId().toString());
        assertThat(record.getValue().value()).contains("ORDER_CONFIRMED");
        verify(outboxRepository).markPublished(eq(message.getId()), any(Instant.class));
    }

    @Test
    @DisplayName("Идентификатор запроса едет в заголовке сообщения")
    void requestIdTravelsInTheHeader() {
        pending(message("trace-1"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(sent());

        publisher.publishBatch();

        ArgumentCaptor<ProducerRecord<String, String>> record = recordCaptor();
        verify(kafkaTemplate).send(record.capture());
        assertThat(new String(record.getValue().headers()
                .lastHeader(RequestTracingFilter.REQUEST_ID_HEADER).value(), StandardCharsets.UTF_8))
                .isEqualTo("trace-1");
    }

    @Test
    @DisplayName("Сообщение без трассы уходит без заголовка")
    void messageWithoutRequestIdHasNoHeader() {
        pending(message(null));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(sent());

        publisher.publishBatch();

        ArgumentCaptor<ProducerRecord<String, String>> record = recordCaptor();
        verify(kafkaTemplate).send(record.capture());
        assertThat(record.getValue().headers().lastHeader(RequestTracingFilter.REQUEST_ID_HEADER)).isNull();
    }

    @Test
    @DisplayName("Отказ брокера останавливает проход: порядок событий заказа важнее полноты пачки")
    void failureStopsTheBatch() {
        OutboxMessage first = message("trace-1");
        OutboxMessage second = message("trace-2");
        pending(first, second);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThat(publisher.publishBatch()).isZero();

        verify(kafkaTemplate).send(any(ProducerRecord.class));
        verify(outboxRepository).markAttempted(first.getId());
        verify(outboxRepository, never()).markPublished(any(), any());
        assertThat(registry.get(ServiceMetrics.ERRORS).tag("type", "outbox_publish_failed")
                .counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("За проход берётся не больше настроенной пачки, и заезженные сообщения не берутся")
    void batchSizeIsRespected() {
        pending();

        publisher.publishBatch();

        verify(outboxRepository).findUnpublished(10, Limit.of(50));
    }

    @Test
    @DisplayName("Исчерпавшее попытки сообщение отмечается отдельной метрикой: его разбирают руками")
    void exhaustedMessageIsParked() {
        OutboxMessage message = message("trace-1");
        set(message, "attempts", 9);
        pending(message);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("too big")));

        publisher.publishBatch();

        assertThat(registry.get(ServiceMetrics.ERRORS).tag("type", "outbox_message_parked")
                .counter().count()).isEqualTo(1);
    }

    private void pending(OutboxMessage... messages) {
        when(outboxRepository.findUnpublished(anyInt(), any(Limit.class)))
                .thenReturn(List.of(messages));
    }

    private static CompletableFuture<SendResult<String, String>> sent() {
        return CompletableFuture.completedFuture(null);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<ProducerRecord<String, String>> recordCaptor() {
        return ArgumentCaptor.forClass(ProducerRecord.class);
    }

    private static OutboxMessage message(String requestId) {
        OutboxMessage message = OutboxMessage.of(UUID.randomUUID(), "order-events",
                OrderEventPayload.ORDER_CONFIRMED,
                "{\"type\":\"ORDER_CONFIRMED\"}", requestId);
        set(message, "id", UUID.randomUUID());
        return message;
    }

    private static void set(OutboxMessage message, String field, Object value) {
        try {
            var declared = OutboxMessage.class.getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(message, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
