package ru.petstore.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import ru.petstore.inventory.kafka.OrderEvent;
import ru.petstore.inventory.service.ReservationService;
import ru.petstore.inventory.service.ReserveLine;
import ru.petstore.inventory.service.StockService;
import ru.petstore.inventory.web.dto.StockRequest;

/**
 * The write-off path end to end: a real broker, the real consumer, the real database — that the
 * message actually arrives and is understood.
 */
@SpringBootTest(properties = {
        "spring.liquibase.contexts=test",
        "grpc.server.port=0",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.consumer.properties.metadata.max.age.ms=1000",
        "spring.kafka.consumer.group-id=inventory-service-test"
})
class OrderEventsKafkaTest extends AbstractPostgresTest {

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    static {
        KAFKA.start();
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private StockService stockService;

    @TestConfiguration
    static class TopicConfig {

        /** In the cluster the topic belongs to {@code order-service}; here nobody creates it, so the test does. */
        @Bean
        NewTopic orderEvents() {
            return new NewTopic("order-events", 3, (short) 1);
        }
    }

    private UUID reserve(UUID product, int quantity) {
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(new ReserveLine(product, quantity)));
        return orderId;
    }

    private void publish(UUID orderId, String type) {
        kafkaTemplate.send("order-events", orderId.toString(), """
                {"eventId":"%s","orderId":"%s","type":"%s","occurredAt":"2026-08-16T12:00:00Z"}"""
                .formatted(UUID.randomUUID(), orderId, type));
    }

    @Test
    @DisplayName("ORDER_CONFIRMED из Kafka списывает остаток")
    void confirmedEventWritesStockOff() {
        UUID product = UUID.randomUUID();
        stockService.set(product, new StockRequest("MSK", 10));
        UUID orderId = reserve(product, 4);

        publish(orderId, OrderEvent.ORDER_CONFIRMED);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(stockService.get(product).quantity()).isEqualTo(6);
            assertThat(stockService.get(product).reserved()).isZero();
        });
    }

    @Test
    @DisplayName("ORDER_CANCELLED из Kafka возвращает остаток в продажу")
    void cancelledEventReleasesTheHold() {
        UUID product = UUID.randomUUID();
        stockService.set(product, new StockRequest("SPB", 10));
        UUID orderId = reserve(product, 4);

        publish(orderId, OrderEvent.ORDER_CANCELLED);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(stockService.get(product).quantity()).isEqualTo(10);
            assertThat(stockService.get(product).reserved()).isZero();
        });
    }
}
