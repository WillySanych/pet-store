package ru.petstore.common.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.common.web.RequestTracingFilter;

/**
 * End-to-end check on a running server: the filters join the chain by themselves,
 * the metrics show up, the errors come back in the common format.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CommonCoreIntegrationTest {

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("Входящий идентификатор возвращается в ответе")
    void incomingRequestIdIsEchoedBack() {
        String incoming = UUID.randomUUID().toString();
        var headers = new HttpHeaders();
        headers.set(RequestTracingFilter.REQUEST_ID_HEADER, incoming);

        var response = testRestTemplate.exchange("/probe/42", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(RequestTracingFilter.REQUEST_ID_HEADER))
                .isEqualTo(incoming);
    }

    @Test
    @DisplayName("В лейбл endpoint попадает шаблон пути, а не фактический URI")
    void endpointTagUsesPathTemplateNotActualUri() {
        // The registry is shared across the class, so measure the delta: otherwise the result
        // would depend on the order the tests run in
        double before = successCount("/probe/{id}");

        testRestTemplate.getForEntity("/probe/111", String.class);
        testRestTemplate.getForEntity("/probe/222", String.class);
        testRestTemplate.getForEntity("/probe/333", String.class);

        assertThat(successCount("/probe/{id}") - before).isEqualTo(3);

        assertThat(endpointTags()).doesNotContain("/probe/111", "/probe/222", "/probe/333");
    }

    @Test
    @DisplayName("Длительность запроса попадает в Timer с тем же шаблоном")
    void requestDurationUsesSamePathTemplate() {
        testRestTemplate.getForEntity("/probe/9", String.class);

        var timer = meterRegistry.get(ServiceMetrics.REQUEST_DURATION)
                .tag("endpoint", "/probe/{id}")
                .timer();

        assertThat(timer.count()).isPositive();
    }

    @Test
    @DisplayName("Ошибка приходит в общем формате с идентификатором запроса")
    void errorUsesCommonFormatWithRequestId() {
        String incoming = UUID.randomUUID().toString();
        var headers = new HttpHeaders();
        headers.set(RequestTracingFilter.REQUEST_ID_HEADER, incoming);

        var response = testRestTemplate.exchange("/probe/boom", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody())
                .contains("\"code\":\"INTERNAL_ERROR\"")
                .contains("\"requestId\":\"" + incoming + "\"");
    }

    @Test
    @DisplayName("Неуспешный запрос считается отдельно от успешного")
    void failedRequestIsCountedSeparately() {
        testRestTemplate.getForEntity("/probe/boom", String.class);

        var failures = meterRegistry.get(ServiceMetrics.REQUESTS)
                .tag("endpoint", "/probe/boom")
                .tag("outcome", "failure")
                .counter();

        assertThat(failures.count()).isPositive();
    }

    @Test
    @DisplayName("Несуществующие пути не плодят отдельные серии метрик")
    void unknownPathsDoNotCreateSeparateSeries() {
        String first = "/no-such-path-" + UUID.randomUUID();
        String second = "/no-such-path-" + UUID.randomUUID();

        testRestTemplate.getForEntity(first, String.class);
        testRestTemplate.getForEntity(second, String.class);

        // Unmatched paths are served by the static resource mapping, whose pattern is "/**".
        // The value itself does not matter — what matters is that random URIs never become
        // labels, otherwise junk traffic would blow up Prometheus.
        assertThat(endpointTags()).doesNotContain(first, second).contains("/**");
    }

    private double successCount(String endpoint) {
        var counter = meterRegistry.find(ServiceMetrics.REQUESTS)
                .tag("endpoint", endpoint)
                .tag("outcome", "success")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private List<String> endpointTags() {
        return meterRegistry.find(ServiceMetrics.REQUESTS).counters().stream()
                .map(c -> c.getId().getTag("endpoint"))
                .toList();
    }
}
