package ru.petstore.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshableReferenceCacheTest {

    @Mock
    private Supplier<Map<String, String>> loader;

    private static Map<String, String> data(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("До первой загрузки кеш не прогрет и пуст")
    void notWarmedUpAndEmptyBeforeFirstLoad() {
        var cache = new RefreshableReferenceCache<>("categories", loader);

        assertThat(cache.isWarmedUp()).isFalse();
        assertThat(cache.size()).isZero();
        assertThat(cache.get("any")).isEmpty();
    }

    @Test
    @DisplayName("После загрузки кеш прогрет и отдаёт данные")
    void warmedUpAndServesDataAfterLoad() {
        when(loader.get()).thenReturn(data("b", "Bravo", "a", "Alpha"));
        var cache = new RefreshableReferenceCache<>("categories", loader);

        cache.refresh();

        assertThat(cache.isWarmedUp()).isTrue();
        assertThat(cache.get("a")).contains("Alpha");
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Значения отдаются отсортированными по ключу")
    void valuesAreReturnedSortedByKey() {
        when(loader.get()).thenReturn(data("c", "Charlie", "a", "Alpha", "b", "Bravo"));
        var cache = new RefreshableReferenceCache<>("categories", loader);

        cache.refresh();

        // Loaded in c, a, b order — ConcurrentSkipListMap returns them ascending by key
        assertThat(cache.all()).containsExactly("Alpha", "Bravo", "Charlie");
        assertThat(cache.range("a", "b")).containsOnlyKeys("a", "b");
    }

    @Test
    @DisplayName("Попадания и промахи считаются раздельно")
    void hitsAndMissesAreCountedSeparately() {
        when(loader.get()).thenReturn(data("a", "Alpha"));
        var cache = new RefreshableReferenceCache<>("categories", loader);
        cache.refresh();

        cache.get("a");
        cache.get("a");
        cache.get("missing");

        assertThat(cache.hits()).isEqualTo(2);
        assertThat(cache.misses()).isEqualTo(1);
    }

    @Test
    @DisplayName("Перезагрузка заменяет содержимое целиком")
    void refreshReplacesEntireContent() {
        when(loader.get())
                .thenReturn(data("a", "Alpha", "b", "Bravo"))
                .thenReturn(data("c", "Charlie"));
        var cache = new RefreshableReferenceCache<>("categories", loader);

        cache.refresh();
        cache.refresh();

        assertThat(cache.get("a")).isEmpty();
        assertThat(cache.get("c")).contains("Charlie");
        assertThat(cache.size()).isEqualTo(1);
        verify(loader, times(2)).get();
    }

    @Test
    @DisplayName("Пустая загрузка не считается прогревом: кеш остаётся закрытым для трафика")
    void emptyFirstLoadDoesNotWarmUpTheCache() {
        when(loader.get()).thenReturn(Map.of());
        var cache = new RefreshableReferenceCache<String, String>("categories", loader);

        cache.refresh();

        assertThat(cache.isWarmedUp()).isFalse();
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("Пустая перезагрузка сохраняет прежнее содержимое, а не обнуляет справочник")
    void emptyRefreshKeepsTheLastGoodContent() {
        when(loader.get())
                .thenReturn(data("a", "Alpha", "b", "Bravo"))
                .thenReturn(Map.of());
        var cache = new RefreshableReferenceCache<>("categories", loader);

        cache.refresh();
        cache.refresh();

        assertThat(cache.get("a")).contains("Alpha");
        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.isWarmedUp()).isTrue();
        verify(loader, times(2)).get();
    }

    @Test
    @DisplayName("Ожидание прогрева отпускается после загрузки")
    void warmUpLatchReleasesAfterLoad() throws Exception {
        var slowLoader = new CountDownLatch(1);
        var cache = new RefreshableReferenceCache<String, String>("categories", () -> {
            try {
                slowLoader.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return data("a", "Alpha");
        });

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            pool.submit(cache::refresh);

            assertThat(cache.awaitWarmUp(Duration.ofMillis(200))).isFalse();
            slowLoader.countDown();
            assertThat(cache.awaitWarmUp(Duration.ofSeconds(2))).isTrue();
        }
    }

    @Test
    @DisplayName("Читатели не видят пустой кеш во время перезагрузки")
    void readersNeverSeeEmptyCacheDuringRefresh() throws Exception {
        var cache = new RefreshableReferenceCache<String, String>("categories",
                () -> data("a", "Alpha", "b", "Bravo"));
        cache.refresh();

        var sawEmpty = new AtomicBoolean(false);
        var stop = new AtomicBoolean(false);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var reader = pool.submit(() -> {
                while (!stop.get()) {
                    if (cache.size() == 0) {
                        sawEmpty.set(true);
                    }
                    Thread.onSpinWait();
                }
            });

            for (int i = 0; i < 100; i++) {
                cache.refresh();
            }
            stop.set(true);
            reader.get(5, TimeUnit.SECONDS);
        }

        // clear() and putAll() run under the write lock — the empty state is not observable
        assertThat(sawEmpty).isFalse();
    }
}
