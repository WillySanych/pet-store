package ru.petstore.order.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import ru.petstore.common.concurrent.MdcPropagation;

/**
 * Virtual threads for upstream calls. Deliberately not an {@code ExecutorService} bean: that would
 * switch off the auto-configured {@code applicationTaskExecutor}.
 */
public class UpstreamExecutor implements AutoCloseable {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** The tracing context of the caller travels with the task; MDC itself is thread local. */
    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        return CompletableFuture.supplyAsync(MdcPropagation.wrap(task), executor);
    }

    @Override
    public void close() {
        executor.close();
    }
}
