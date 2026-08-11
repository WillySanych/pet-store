package ru.petstore.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.common.overload.OverloadedException;

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
            throw new OverloadedException("/overloaded");
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("something went wrong");
        }

        @GetMapping("/bad-argument")
        String badArgument() {
            throw new IllegalArgumentException("invalid argument");
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
    @DisplayName("Некорректный аргумент отдаёт 400")
    void illegalArgumentReturns400() throws Exception {
        mockMvc.perform(get("/bad-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
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
