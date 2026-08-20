package ru.petstore.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "petstore.order")
public class OrderProperties {

    private String customerServiceUrl = "http://localhost:8083";

    private Duration customerTimeout = Duration.ofSeconds(2);

    private Duration upstreamDeadline = Duration.ofSeconds(2);

    private String orderEventsTopic = "order-events";

    private int orderEventsPartitions = 3;

    private short orderEventsReplicas = 1;

    private int outboxBatchSize = 50;

    private int outboxMaxAttempts = 10;

    private Duration outboxPollInterval = Duration.ofSeconds(1);

    private Duration outboxSendTimeout = Duration.ofSeconds(5);

    public String getCustomerServiceUrl() {
        return customerServiceUrl;
    }

    public void setCustomerServiceUrl(String customerServiceUrl) {
        this.customerServiceUrl = customerServiceUrl;
    }

    public Duration getCustomerTimeout() {
        return customerTimeout;
    }

    public void setCustomerTimeout(Duration customerTimeout) {
        this.customerTimeout = customerTimeout;
    }

    public Duration getUpstreamDeadline() {
        return upstreamDeadline;
    }

    public void setUpstreamDeadline(Duration upstreamDeadline) {
        this.upstreamDeadline = upstreamDeadline;
    }

    public String getOrderEventsTopic() {
        return orderEventsTopic;
    }

    public void setOrderEventsTopic(String orderEventsTopic) {
        this.orderEventsTopic = orderEventsTopic;
    }

    public int getOrderEventsPartitions() {
        return orderEventsPartitions;
    }

    public void setOrderEventsPartitions(int orderEventsPartitions) {
        this.orderEventsPartitions = orderEventsPartitions;
    }

    public short getOrderEventsReplicas() {
        return orderEventsReplicas;
    }

    public void setOrderEventsReplicas(short orderEventsReplicas) {
        this.orderEventsReplicas = orderEventsReplicas;
    }

    public int getOutboxBatchSize() {
        return outboxBatchSize;
    }

    public void setOutboxBatchSize(int outboxBatchSize) {
        this.outboxBatchSize = outboxBatchSize;
    }

    public int getOutboxMaxAttempts() {
        return outboxMaxAttempts;
    }

    public void setOutboxMaxAttempts(int outboxMaxAttempts) {
        this.outboxMaxAttempts = outboxMaxAttempts;
    }

    public Duration getOutboxPollInterval() {
        return outboxPollInterval;
    }

    public void setOutboxPollInterval(Duration outboxPollInterval) {
        this.outboxPollInterval = outboxPollInterval;
    }

    public Duration getOutboxSendTimeout() {
        return outboxSendTimeout;
    }

    public void setOutboxSendTimeout(Duration outboxSendTimeout) {
        this.outboxSendTimeout = outboxSendTimeout;
    }
}
