package ru.petstore.common.reference;

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

    private enum TestType implements ReferenceKind {
        CATEGORY,
        SPECIES,
        RESERVATION_STATUS
    }

    private ReferenceDataService referenceDataService;

    @BeforeEach
    void setUp() {
        referenceDataService = new ReferenceDataService(TestType.values(), Map.of(
                "categoryCache", warm("categories", Map.of(
                        "TOYS", new ReferenceItem(2L, "TOYS", "Игрушки"),
                        "FOOD", new ReferenceItem(1L, "FOOD", "Корма"))),
                "speciesCache", warm("species", Map.of(
                        "DOG", new ReferenceItem(10L, "DOG", "Собаки"))),
                "reservationStatusCache", warm("reservation-statuses", Map.of(
                        "ACTIVE", new ReferenceItem(20L, "ACTIVE", "Активен")))));
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
        assertThat(referenceDataService.getAll(TestType.CATEGORY))
                .extracting(ReferenceItem::code)
                .containsExactly("FOOD", "TOYS");
    }

    @Test
    @DisplayName("Известный код резолвится в элемент справочника")
    void knownCodeResolvesToItem() {
        assertThat(referenceDataService.getRequired(TestType.SPECIES, "DOG").id()).isEqualTo(10L);
        assertThat(referenceDataService.getIdOrNull(TestType.RESERVATION_STATUS, "ACTIVE")).isEqualTo(20L);
    }

    @Test
    @DisplayName("Составное имя справочника даёт camelCase имя бина кеша")
    void twoWordReferenceTypeMapsToCamelCaseBeanName() {
        assertThat(TestType.RESERVATION_STATUS.cacheBeanName()).isEqualTo("reservationStatusCache");
        assertThat(TestType.CATEGORY.cacheBeanName()).isEqualTo("categoryCache");
    }

    @Test
    @DisplayName("Неизвестный код — ошибка клиента, а не пустой результат")
    void unknownCodeIsRejected() {
        assertThatThrownBy(() -> referenceDataService.getRequired(TestType.CATEGORY, "NOPE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown category code: NOPE");
    }

    @Test
    @DisplayName("Отсутствующий фильтр не превращается в условие")
    void absentFilterResolvesToNull() {
        assertThat(referenceDataService.getIdOrNull(TestType.CATEGORY, null)).isNull();
        assertThat(referenceDataService.getIdOrNull(TestType.CATEGORY, "  ")).isNull();
    }

    @Test
    @DisplayName("Неизвестный код в фильтре тоже отклоняется")
    void unknownFilterCodeIsRejected() {
        assertThatThrownBy(() -> referenceDataService.getIdOrNull(TestType.SPECIES, "DRAGON"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Непрогретый кеш отвечает 503, а не выдуманным «неизвестным кодом»")
    void coldCacheIsReportedAsUnavailable() {
        var cold = new ReferenceDataService(TestType.values(), Map.of(
                "categoryCache", cache("categories", Map.of()),
                "speciesCache", cache("species", Map.of()),
                "reservationStatusCache", cache("reservation-statuses", Map.of())));

        assertThatThrownBy(() -> cold.getRequired(TestType.CATEGORY, "FOOD"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("not loaded yet");
        assertThatThrownBy(() -> cold.getAll(TestType.SPECIES))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    @DisplayName("Справочник без своего кеша роняет старт, а не первый запрос")
    void referenceTypeWithoutCacheFailsAtStartup() {
        assertThatThrownBy(() -> new ReferenceDataService(TestType.values(), Map.of(
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

        var referenceDataService = new ReferenceDataService(TestType.values(), Map.of(
                "categoryCache", categories,
                "speciesCache", warm("species", Map.of("DOG", new ReferenceItem(10L, "DOG", "Собаки"))),
                "reservationStatusCache", warm("reservation-statuses",
                        Map.of("ACTIVE", new ReferenceItem(20L, "ACTIVE", "Активен")))));

        assertThat(referenceDataService.getRequired(TestType.CATEGORY, "FOOD").id()).isEqualTo(1L);
    }
}
