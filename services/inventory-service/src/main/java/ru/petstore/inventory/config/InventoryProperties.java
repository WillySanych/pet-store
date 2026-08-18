package ru.petstore.inventory.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "petstore.inventory")
public class InventoryProperties {

    /** How long a hold survives without a confirmation or a cancellation; the sweeper gives it back afterwards. */
    private Duration reservationTtl = Duration.ofMinutes(15);

    /** Overdue holds released per sweep; the rest wait for the next pass. */
    private int expiryBatchSize = 200;

    /** The topic {@code order-service} publishes to; the consumer group is set in {@code spring.kafka}. */
    private String orderEventsTopic = "order-events";

    public Duration getReservationTtl() {
        return reservationTtl;
    }

    public void setReservationTtl(Duration reservationTtl) {
        this.reservationTtl = reservationTtl;
    }

    public int getExpiryBatchSize() {
        return expiryBatchSize;
    }

    public void setExpiryBatchSize(int expiryBatchSize) {
        this.expiryBatchSize = expiryBatchSize;
    }

    public String getOrderEventsTopic() {
        return orderEventsTopic;
    }

    public void setOrderEventsTopic(String orderEventsTopic) {
        this.orderEventsTopic = orderEventsTopic;
    }
}
