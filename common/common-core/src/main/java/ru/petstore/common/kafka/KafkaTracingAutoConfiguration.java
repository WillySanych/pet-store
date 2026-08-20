package ru.petstore.common.kafka;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.listener.RecordInterceptor;

/** Boot hands a single {@link RecordInterceptor} bean to the listener container factory. */
@AutoConfiguration(before = KafkaAutoConfiguration.class)
@ConditionalOnClass(RecordInterceptor.class)
public class KafkaTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RecordInterceptor.class)
    public RequestIdRecordInterceptor requestIdRecordInterceptor() {
        return new RequestIdRecordInterceptor();
    }
}
