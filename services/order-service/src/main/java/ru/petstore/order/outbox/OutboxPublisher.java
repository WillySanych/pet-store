package ru.petstore.order.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.common.web.RequestTracingFilter;
import ru.petstore.order.config.OrderProperties;
import ru.petstore.order.domain.OutboxMessage;
import ru.petstore.order.repository.OutboxRepository;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ServiceMetrics serviceMetrics;
    private final OrderProperties properties;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           ServiceMetrics serviceMetrics,
                           OrderProperties properties) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.serviceMetrics = serviceMetrics;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${petstore.order.outbox-poll-interval:PT1S}",
            initialDelayString = "${petstore.order.outbox-poll-interval:PT1S}")
    @SchedulerLock(name = "order-outbox-publisher", lockAtLeastFor = "PT1S", lockAtMostFor = "PT10M")
    public void publishPending() {
        try {
            publishBatch();
        } catch (RuntimeException e) {
            log.error("Outbox pass failed, retrying on the next one", e);
        }
    }

    public int publishBatch() {
        List<OutboxMessage> pending = outboxRepository.findUnpublished(
                properties.getOutboxMaxAttempts(), Limit.of(properties.getOutboxBatchSize()));

        int published = 0;
        for (OutboxMessage message : pending) {
            if (!publish(message)) {
                break;
            }
            published++;
        }
        if (published > 0) {
            log.debug("Published {} outbox message(s)", published);
        }
        return published;
    }

    private boolean publish(OutboxMessage message) {
        putRequestId(message);
        try {
            kafkaTemplate.send(record(message))
                    .get(properties.getOutboxSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            outboxRepository.markPublished(message.getId(), Instant.now());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Outbox publishing interrupted on message {}", message.getId());
            return false;
        } catch (ExecutionException | RuntimeException | java.util.concurrent.TimeoutException e) {
            outboxRepository.markAttempted(message.getId());
            serviceMetrics.recordError("outbox_publish_failed");
            int attempts = message.getAttempts() + 1;
            log.error("Failed to publish event {} of order {}, attempt {}",
                    message.getType(), message.getAggregateId(), attempts, e);
            if (attempts >= properties.getOutboxMaxAttempts()) {
                serviceMetrics.recordError("outbox_message_parked");
                log.error("Event {} of order {} is parked after {} attempts and needs a look",
                        message.getType(), message.getAggregateId(), attempts);
            }
            return false;
        } finally {
            MDC.remove(RequestTracingFilter.MDC_KEY);
        }
    }

    private static ProducerRecord<String, String> record(OutboxMessage message) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                message.getTopic(), message.getAggregateId().toString(), message.getPayload());
        if (message.getRequestId() != null) {
            record.headers().add(RequestTracingFilter.REQUEST_ID_HEADER,
                    message.getRequestId().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    private static void putRequestId(OutboxMessage message) {
        if (message.getRequestId() != null) {
            MDC.put(RequestTracingFilter.MDC_KEY, message.getRequestId());
        }
    }
}
