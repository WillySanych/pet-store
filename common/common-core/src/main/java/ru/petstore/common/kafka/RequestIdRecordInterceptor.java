package ru.petstore.common.kafka;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import ru.petstore.common.web.RequestTracingFilter;

/**
 * Continues the trace on the consumer side: the request id sent in the header of the message goes
 * into MDC for the duration of its handling.
 */
public class RequestIdRecordInterceptor implements RecordInterceptor<Object, Object> {

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        Header header = record.headers().lastHeader(RequestTracingFilter.REQUEST_ID_HEADER);
        if (header != null && header.value() != null) {
            MDC.put(RequestTracingFilter.MDC_KEY, new String(header.value(), StandardCharsets.UTF_8));
        }
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        MDC.remove(RequestTracingFilter.MDC_KEY);
    }
}
