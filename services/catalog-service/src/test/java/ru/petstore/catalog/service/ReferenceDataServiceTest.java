package ru.petstore.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.petstore.common.cache.RefreshableReferenceCache;
import ru.petstore.common.web.ServiceUnavailableException;

class ReferenceDataServiceTest {

    private ReferenceDataService references;

    @BeforeEach
    void setUp() {
        references = new ReferenceDataService(Map.of(
                "categoryCache", warm("categories", Map.of(
                        "TOYS", new ReferenceItem(2L, "TOYS", "Игрушки"),
                        "FOOD", new ReferenceItem(1L, "FOOD", "Корма"))),
                "speciesCache", warm("species", Map.of(
                        "DOG", new ReferenceItem(10L, "DOG", "Собаки"))),
                "brandCache", warm("brands", Map.of(
                        "TRIXIE", new ReferenceItem(20L, "TRIXIE", "Trixie")))));
    }

    private static RefreshableReferenceCache<String, ReferenceItem> cache(
            String name, Map<String, ReferenceItem> content) {
        return new RefreshableReferenceCache<>(name, () -> new LinkedHashMap<>(content));
    }

    private static RefreshableReferenceCache<String, ReferenceItem> warm(
            String name, Map<String, ReferenceItem> content) {
        var cache = cache(name, content);
        cache.refresh();
        return cache;
    }

    @Test
    @DisplayName("Справочник отдаётся отсортированным по коду")
    void referenceTableIsReturnedSortedByCode() {
        assertThat(references.getAll(ReferenceType.CATEGORY))
                .extracting(ReferenceItem::code)
                .containsExactly("FOOD", "TOYS");
    }

    @Test
    @DisplayName("Известный код резолвится в элемент справочника")
    void knownCodeResolvesToItem() {
        assertThat(references.getRequired(ReferenceType.SPECIES, "DOG").id()).isEqualTo(10L);
        assertThat(references.getIdOrNull(ReferenceType.BRAND, "TRIXIE")).isEqualTo(20L);
    }

    @Test
    @DisplayName("Неизвестный код — ошибка клиента, а не пустой результат")
    void unknownCodeIsRejected() {
        assertThatThrownBy(() -> references.getRequired(ReferenceType.CATEGORY, "NOPE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown category code: NOPE");
    }

    @Test
    @DisplayName("Отсутствующий фильтр не превращается в условие")
    void absentFilterResolvesToNull() {
        assertThat(references.getIdOrNull(ReferenceType.CATEGORY, null)).isNull();
        assertThat(references.getIdOrNull(ReferenceType.CATEGORY, "  ")).isNull();
    }

    @Test
    @DisplayName("Неизвестный код в фильтре тоже отклоняется")
    void unknownFilterCodeIsRejected() {
        assertThatThrownBy(() -> references.getIdOrNull(ReferenceType.SPECIES, "DRAGON"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Непрогретый кеш отвечает 503, а не выдуманным «неизвестным кодом»")
    void coldCacheIsReportedAsUnavailable() {
        var cold = new ReferenceDataService(Map.of(
                "categoryCache", cache("categories", Map.of()),
                "speciesCache", cache("species", Map.of()),
                "brandCache", cache("brands", Map.of())));

        assertThatThrownBy(() -> cold.getRequired(ReferenceType.CATEGORY, "FOOD"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("not loaded yet");
        assertThatThrownBy(() -> cold.getAll(ReferenceType.BRAND))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    @DisplayName("Справочник без своего кеша роняет старт, а не первый запрос")
    void referenceTypeWithoutCacheFailsAtStartup() {
        assertThatThrownBy(() -> new ReferenceDataService(Map.of(
                "categoryCache", cache("categories", Map.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("speciesCache");
    }

    @Test
    @DisplayName("Опустевший справочник отвечает последним удачным содержимым, а не «неизвестным кодом»")
    void emptiedReferenceTableKeepsServingTheLastGoodContent() {
        var content = new AtomicReference<Map<String, ReferenceItem>>(
                Map.of("FOOD", new ReferenceItem(1L, "FOOD", "Корма")));
        var categories = new RefreshableReferenceCache<String, ReferenceItem>(
                "categories", () -> new LinkedHashMap<>(content.get()));
        categories.refresh();

        content.set(Map.of());
        categories.refresh();

        var references = new ReferenceDataService(Map.of(
                "categoryCache", categories,
                "speciesCache", warm("species", Map.of("DOG", new ReferenceItem(10L, "DOG", "Собаки"))),
                "brandCache", warm("brands", Map.of("TRIXIE", new ReferenceItem(20L, "TRIXIE", "Trixie")))));

        assertThat(references.getRequired(ReferenceType.CATEGORY, "FOOD").id()).isEqualTo(1L);
    }
}
