package ru.petstore.order.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.kafka.KafkaContainer;
import ru.petstore.order.config.OrderProperties;
import ru.petstore.order.domain.OutboxMessage;
import ru.petstore.order.outbox.OrderEventPayload;
import ru.petstore.order.outbox.OutboxPublisher;
import ru.petstore.order.repository.OutboxRepository;
import ru.petstore.common.web.RequestTracingFilter;

@SpringBootTest(properties = "petstore.order.outbox-poll-interval=PT1H")
class OutboxKafkaTest extends AbstractPostgresTest {

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    static {
        KAFKA.start();
    }

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OrderProperties properties;

    @Test
    @DisplayName("Событие уходит в Kafka ключом заказа, с трассой в заголовке, и помечается опубликованным")
    void eventReachesKafka() {
        UUID orderId = UUID.randomUUID();
        OutboxMessage message = outboxRepository.saveAndFlush(OutboxMessage.of(
                orderId, properties.getOrderEventsTopic(), OrderEventPayload.ORDER_CONFIRMED,
                "{\"eventId\":\"" + UUID.randomUUID() + "\",\"orderId\":\"" + orderId
                        + "\",\"type\":\"ORDER_CONFIRMED\"}",
                "trace-1"));

        assertThat(publisher.publishBatch()).isPositive();

        ConsumerRecord<String, String> record = consume(orderId);
        assertThat(record.key()).isEqualTo(orderId.toString());
        assertThat(record.value()).contains("ORDER_CONFIRMED");
        assertThat(new String(record.headers().lastHeader(RequestTracingFilter.REQUEST_ID_HEADER).value(),
                StandardCharsets.UTF_8)).isEqualTo("trace-1");

        assertThat(outboxRepository.findById(message.getId()).orElseThrow().getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("Топик заводит продюсер: три партиции появляются без консьюмеров")
    void topicIsCreatedByTheProducer() {
        try (KafkaConsumer<String, String> consumer = consumer()) {
            assertThat(consumer.partitionsFor(properties.getOrderEventsTopic()))
                    .hasSize(properties.getOrderEventsPartitions());
        }
    }

    private ConsumerRecord<String, String> consume(UUID orderId) {
        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(properties.getOrderEventsTopic()));
            List<ConsumerRecord<String, String>> received = new ArrayList<>();

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                records.forEach(received::add);
                assertThat(received).anyMatch(record -> orderId.toString().equals(record.key()));
            });

            return received.stream()
                    .filter(record -> orderId.toString().equals(record.key()))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private KafkaConsumer<String, String> consumer() {
        Properties config = new Properties();
        config.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        return new KafkaConsumer<>(config);
    }
}
