package ru.petstore.common.concurrent;

import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * Carries the tracing context into another thread: MDC is thread local, so a task handed to a
 * virtual-thread executor would otherwise log without {@code requestId}.
 */
public final class MdcPropagation {

    private MdcPropagation() {
    }

    public static <T> Supplier<T> wrap(Supplier<T> task) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            apply(captured);
            try {
                return task.get();
            } finally {
                apply(previous);
            }
        };
    }

    public static Runnable wrap(Runnable task) {
        Supplier<Void> wrapped = wrap(() -> {
            task.run();
            return null;
        });
        return wrapped::get;
    }

    private static void apply(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
