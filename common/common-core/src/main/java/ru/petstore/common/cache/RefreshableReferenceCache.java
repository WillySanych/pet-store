package ru.petstore.common.cache;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reference data cache
 */
public class RefreshableReferenceCache<K extends Comparable<K>, V> {

    private static final Logger log = LoggerFactory.getLogger(RefreshableReferenceCache.class);

    private final String name;
    private final Supplier<Map<K, V>> loader;

    private final ConcurrentHashMap<K, V> byKey = new ConcurrentHashMap<>();
    private final ConcurrentSkipListMap<K, V> sorted = new ConcurrentSkipListMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    /** Opens after the first load that brought data; the readiness probe relies on it. */
    private final CountDownLatch warmedUp = new CountDownLatch(1);

    public RefreshableReferenceCache(String name, Supplier<Map<K, V>> loader) {
        this.name = name;
        this.loader = loader;
    }

    public Optional<V> get(K key) {
        lock.readLock().lock();
        try {
            V value = byKey.get(key);
            if (value == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            hits.incrementAndGet();
            return Optional.of(value);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<V> all() {
        lock.readLock().lock();
        try {
            return List.copyOf(sorted.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public NavigableMap<K, V> range(K fromInclusive, K toInclusive) {
        lock.readLock().lock();
        try {
            return new java.util.TreeMap<>(sorted.subMap(fromInclusive, true, toInclusive, true));
        } finally {
            lock.readLock().unlock();
        }
    }

    public void refresh() {
        Map<K, V> fresh = loader.get();
        if (fresh.isEmpty()) {
            if (size() > 0) {
                log.warn("Cache {} not replaced: the loader returned nothing, keeping {} entries",
                        name, size());
            } else {
                log.warn("Cache {} is still empty after a load; readiness stays down", name);
            }
            return;
        }

        lock.writeLock().lock();
        try {
            byKey.clear();
            byKey.putAll(fresh);
            sorted.clear();
            sorted.putAll(fresh);
        } finally {
            lock.writeLock().unlock();
        }
        warmedUp.countDown();
    }

    public boolean isWarmedUp() {
        return warmedUp.getCount() == 0;
    }

    public boolean awaitWarmUp(Duration timeout) throws InterruptedException {
        return warmedUp.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public String name() {
        return name;
    }

    public long size() {
        lock.readLock().lock();
        try {
            return byKey.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }
}
