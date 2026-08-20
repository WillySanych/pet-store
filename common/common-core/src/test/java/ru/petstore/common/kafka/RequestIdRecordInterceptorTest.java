package ru.petstore.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import ru.petstore.common.web.RequestTracingFilter;

class RequestIdRecordInterceptorTest {

    private final RequestIdRecordInterceptor interceptor = new RequestIdRecordInterceptor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("requestId из заголовка сообщения попадает в MDC и снимается после обработки")
    void headerBecomesMdcAndIsCleared() {
        ConsumerRecord<Object, Object> record = record("trace-1");

        interceptor.intercept(record, null);
        assertThat(MDC.get(RequestTracingFilter.MDC_KEY)).isEqualTo("trace-1");

        interceptor.afterRecord(record, null);
        assertThat(MDC.get(RequestTracingFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("Сообщение без заголовка не подменяет чужой контекст")
    void recordWithoutHeaderKeepsCurrentContext() {
        MDC.put(RequestTracingFilter.MDC_KEY, "existing");

        interceptor.intercept(record(null), null);

        assertThat(MDC.get(RequestTracingFilter.MDC_KEY)).isEqualTo("existing");
    }

    @Test
    @DisplayName("Перехватчик отдаёт сообщение дальше как есть")
    void recordIsPassedThrough() {
        ConsumerRecord<Object, Object> record = record("trace-1");

        assertThat(interceptor.intercept(record, null)).isSameAs(record);
    }

    private static ConsumerRecord<Object, Object> record(String requestId) {
        ConsumerRecord<Object, Object> record =
                new ConsumerRecord<>("order-events", 0, 0L, "key", "{}");
        if (requestId != null) {
            record.headers().add(RequestTracingFilter.REQUEST_ID_HEADER,
                    requestId.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
