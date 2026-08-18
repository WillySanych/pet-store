package ru.petstore.inventory.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * What happens to a message the listener could not handle. The default of Spring Kafka is ten
 * attempts with no pause at all, which a database outage of even a second burns through — the event
 * would then be dropped and the stock never written off. Here the retries are spread out, and what
 * still fails goes to {@code <topic>.DLT} instead of disappearing.
 */
@Configuration
public class KafkaConfig {

    private static final String DLT_SUFFIX = ".DLT";

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> kafkaTemplate) {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(180_000L);
        return new DefaultErrorHandler(deadLetterRecoverer(kafkaTemplate), backOff);
    }

    /** The partition is kept: events of one order stay in order in the dead-letter topic too. */
    private static DeadLetterPublishingRecoverer deadLetterRecoverer(KafkaTemplate<?, ?> kafkaTemplate) {
        return new DeadLetterPublishingRecoverer(kafkaTemplate, KafkaConfig::deadLetterTopic);
    }

    private static TopicPartition deadLetterTopic(ConsumerRecord<?, ?> record, Exception e) {
        return new TopicPartition(record.topic() + DLT_SUFFIX, record.partition());
    }
}
