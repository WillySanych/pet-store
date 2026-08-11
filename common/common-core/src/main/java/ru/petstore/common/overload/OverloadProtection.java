package ru.petstore.common.overload;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import ru.petstore.common.metrics.ServiceMetrics;

/**
 * Overload protection following the bulkhead pattern: limits the number of concurrently
 * handled requests with a {@link Semaphore} from {@code java.util.concurrent}.
 */
public class OverloadProtection {

    private final Semaphore permits;
    private final ServiceMetrics metrics;

    public OverloadProtection(int maxConcurrent, ServiceMetrics metrics) {
        this.permits = new Semaphore(maxConcurrent);
        this.metrics = metrics;
    }

    public <T> T call(String endpoint, Supplier<T> action) {
        if (!permits.tryAcquire()) {
            metrics.recordOverloadRejected(endpoint);
            throw new OverloadedException(endpoint);
        }
        try {
            return action.get();
        } finally {
            permits.release();
        }
    }

    public void run(String endpoint, Runnable action) {
        call(endpoint, () -> {
            action.run();
            return null;
        });
    }

    /** Free permits — for the saturation metric and diagnostics. */
    public int availablePermits() {
        return permits.availablePermits();
    }
}
