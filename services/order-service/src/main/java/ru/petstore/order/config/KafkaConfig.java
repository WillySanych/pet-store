package ru.petstore.order.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic orderEventsTopic(OrderProperties properties) {
        return TopicBuilder.name(properties.getOrderEventsTopic())
                .partitions(properties.getOrderEventsPartitions())
                .replicas(properties.getOrderEventsReplicas())
                .build();
    }
}
