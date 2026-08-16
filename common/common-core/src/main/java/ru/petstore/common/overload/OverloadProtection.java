package ru.petstore.common.overload;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import ru.petstore.common.metrics.ServiceMetrics;

/**
 * Overload protection following the bulkhead pattern: limits the number of concurrently
 * handled requests with a {@link Semaphore}.
 */
public class OverloadProtection {

    private final Semaphore permits;
    private final ServiceMetrics serviceMetrics;

    public OverloadProtection(int maxConcurrent, ServiceMetrics serviceMetrics) {
        this.permits = new Semaphore(maxConcurrent);
        this.serviceMetrics = serviceMetrics;
    }

    public <T> T call(String endpoint, Supplier<T> action) {
        if (!permits.tryAcquire()) {
            serviceMetrics.recordOverloadRejected(endpoint);
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
