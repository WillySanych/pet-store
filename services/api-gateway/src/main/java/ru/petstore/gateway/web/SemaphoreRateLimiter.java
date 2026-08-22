package ru.petstore.gateway.web;

import java.util.concurrent.Semaphore;

public class SemaphoreRateLimiter {

    private final Semaphore permits;
    private final int limitForPeriod;

    public SemaphoreRateLimiter(int limitForPeriod) {
        this.limitForPeriod = limitForPeriod;
        this.permits = new Semaphore(limitForPeriod);
    }

    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    public int limitForPeriod() {
        return limitForPeriod;
    }

    public void refill() {
        int missing = limitForPeriod - permits.availablePermits();
        if (missing > 0) {
            permits.release(missing);
        }
    }
}
