package ru.petstore.common.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcPropagationTest {

    private static final String KEY = "requestId";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("Контекст вызывающего доезжает до другого потока")
    void contextTravelsToAnotherThread() throws Exception {
        MDC.put(KEY, "trace-1");
        Supplier<String> task = MdcPropagation.wrap(() -> MDC.get(KEY));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(CompletableFuture.supplyAsync(task, executor).get()).isEqualTo("trace-1");
        }
    }

    @Test
    @DisplayName("Задача снимается на момент обёртывания, а не выполнения")
    void contextIsCapturedAtWrapTime() throws Exception {
        MDC.put(KEY, "trace-1");
        Supplier<String> task = MdcPropagation.wrap(() -> MDC.get(KEY));
        MDC.put(KEY, "trace-2");

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(CompletableFuture.supplyAsync(task, executor).get()).isEqualTo("trace-1");
        }
    }

    @Test
    @DisplayName("Поток исполнителя возвращается к своему контексту")
    void executingThreadKeepsItsOwnContext() {
        MDC.put(KEY, "caller");
        Runnable task = MdcPropagation.wrap(() -> {
            assertThat(MDC.get(KEY)).isEqualTo("caller");
        });

        MDC.put(KEY, "worker");
        task.run();

        assertThat(MDC.get(KEY)).isEqualTo("worker");
    }

    @Test
    @DisplayName("Пустой контекст вызывающего очищает контекст исполнителя")
    void emptyCallerContextClearsTheWorker() {
        MDC.clear();
        Runnable task = MdcPropagation.wrap(() -> {
            assertThat(MDC.get(KEY)).isNull();
        });

        MDC.put(KEY, "worker");
        task.run();

        assertThat(MDC.get(KEY)).isEqualTo("worker");
    }
}
