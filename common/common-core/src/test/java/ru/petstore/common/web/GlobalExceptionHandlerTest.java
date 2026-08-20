package ru.petstore.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.petstore.common.metrics.ServiceMetrics;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;

class GlobalExceptionHandlerTest {

    record Payload(String name) {
    }

    @RestController
    static class TestController {

        @GetMapping("/only-get")
        String onlyGet() {
            return "ok";
        }

        @PostMapping("/echo")
        String echo(@RequestBody Payload payload) {
            return payload.name();
        }

        @GetMapping("/overloaded")
        String overloaded() {
            throw BulkheadFullException.createBulkheadFullException(Bulkhead.ofDefaults("overload"));
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("something went wrong");
        }

        @GetMapping("/bad-argument")
        String badArgument() {
            throw new IllegalArgumentException("invalid argument");
        }

        @GetMapping("/missing")
        String missing() {
            throw ResourceNotFoundException.of("Product", 42);
        }

        @GetMapping("/not-ready")
        String notReady() {
            throw new ServiceUnavailableException("Reference data category is not loaded yet");
        }

        @GetMapping("/raced")
        String raced() {
            throw new ConcurrentChangeException("Stock for product 42 changed concurrently",
                    new IllegalStateException("version mismatch"));
        }

        @GetMapping("/stale")
        String stale() {
            throw new OptimisticLockingFailureException("Order " + UUID.randomUUID() + " changed");
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler(new ServiceMetrics(new SimpleMeterRegistry())))
            .build();

    @Test
    @DisplayName("Перегрузка отдаёт 429")
    void overloadReturns429() throws Exception {
        mockMvc.perform(get("/overloaded"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("OVERLOADED"));
    }

    @Test
    @DisplayName("Проигранная гонка отдаёт 409 с Retry-After, а не 500")
    void concurrentChangeReturns409() throws Exception {
        mockMvc.perform(get("/raced"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("CONCURRENT_CHANGE"));
    }

    @Test
    @DisplayName("Проигранная оптимистичная блокировка — тоже 409, а не 500")
    void optimisticLockFailureReturns409() throws Exception {
        mockMvc.perform(get("/stale"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("CONCURRENT_CHANGE"));
    }

    @Test
    @DisplayName("Некорректный аргумент отдаёт 400")
    void illegalArgumentReturns400() throws Exception {
        mockMvc.perform(get("/bad-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("Отсутствующая сущность отдаёт 404, а не 500")
    void notFoundReturns404() throws Exception {
        mockMvc.perform(get("/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Product 42 not found"));
    }

    @Test
    @DisplayName("Неготовность сервиса отдаёт 503, а не 400: клиенту есть смысл повторить")
    void notReadyReturns503() throws Exception {
        mockMvc.perform(get("/not-ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Reference data category is not loaded yet"));
    }

    @Test
    @DisplayName("503 несёт Retry-After: иначе интервал повтора остаётся на усмотрение клиента")
    void notReadyCarriesRetryAfter() throws Exception {
        mockMvc.perform(get("/not-ready"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"));
    }

    @Test
    @DisplayName("Неожиданная ошибка отдаёт 500 без деталей наружу")
    void unexpectedErrorReturns500WithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal service error"));
    }

    @Test
    @DisplayName("Неверный HTTP-метод отдаёт 405, а не 500")
    void wrongHttpMethodReturns405() throws Exception {
        mockMvc.perform(post("/only-get"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("Битый JSON отдаёт 400, а не 500")
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json "))
                .andExpect(status().isBadRequest());
    }
}
