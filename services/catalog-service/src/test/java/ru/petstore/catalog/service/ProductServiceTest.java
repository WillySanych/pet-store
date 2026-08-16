package ru.petstore.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import ru.petstore.catalog.domain.Brand;
import ru.petstore.catalog.domain.Category;
import ru.petstore.catalog.domain.Product;
import ru.petstore.catalog.domain.ReferenceEntity;
import ru.petstore.catalog.domain.Species;
import ru.petstore.catalog.repository.BrandRepository;
import ru.petstore.catalog.repository.CategoryRepository;
import ru.petstore.catalog.repository.ProductRepository;
import ru.petstore.catalog.repository.SpeciesRepository;
import ru.petstore.catalog.web.dto.ProductFilterRequest;
import ru.petstore.catalog.web.dto.ProductRequest;
import ru.petstore.common.web.ResourceNotFoundException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductServiceTest {

    @Mock
    private ProductRepository products;
    @Mock
    private CategoryRepository categories;
    @Mock
    private SpeciesRepository species;
    @Mock
    private BrandRepository brands;
    @Mock
    private ReferenceDataService references;

    @InjectMocks
    private ProductService service;

    private static final ProductFilterRequest NO_FILTER =
            new ProductFilterRequest(null, null, null, null);

    private static final ReferenceItem FOOD = new ReferenceItem(1L, "FOOD", "Корма");
    private static final ReferenceItem DOG = new ReferenceItem(10L, "DOG", "Собаки");
    private static final ReferenceItem TRIXIE = new ReferenceItem(20L, "TRIXIE", "Trixie");

    private static ProductRequest request(String sku) {
        return request(sku, null);
    }

    private static ProductRequest request(String sku, Boolean active) {
        return new ProductRequest(sku, "Корм", "Описание", new BigDecimal("2499.00"),
                "FOOD", "DOG", "TRIXIE", active);
    }

    private static Product product(UUID id, String sku) {
        var product = new Product();
        product.setId(id);
        product.setSku(sku);
        product.setName("Корм");
        product.setPrice(new BigDecimal("2499.00"));
        product.setCategory(reference(Category::new, FOOD));
        product.setSpecies(reference(Species::new, DOG));
        product.setBrand(reference(Brand::new, TRIXIE));
        return product;
    }

    private static <T extends ReferenceEntity> T reference(Supplier<T> factory, ReferenceItem item) {
        T entity = factory.get();
        entity.setId(item.id());
        entity.setCode(item.code());
        entity.setName(item.name());
        return entity;
    }

    private void referencesResolve() {
        when(references.getRequired(ReferenceType.CATEGORY, "FOOD")).thenReturn(FOOD);
        when(references.getRequired(ReferenceType.SPECIES, "DOG")).thenReturn(DOG);
        when(references.getRequired(ReferenceType.BRAND, "TRIXIE")).thenReturn(TRIXIE);
        when(categories.getReferenceById(FOOD.id())).thenReturn(new Category());
        when(species.getReferenceById(DOG.id())).thenReturn(new Species());
        when(brands.getReferenceById(TRIXIE.id())).thenReturn(new Brand());
    }

    private static DataIntegrityViolationException violation(String cause) {
        return new DataIntegrityViolationException("could not execute statement",
                new SQLException(cause));
    }

    @Test
    @DisplayName("Пустой фильтр не мешает выдаче и отдаёт метаданные страницы")
    void emptyFilterReturnsPageMetadata() {
        var pageable = PageRequest.of(1, 2);
        when(products.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(product(UUID.randomUUID(), "SKU-1")), pageable, 7));

        var page = service.search(NO_FILTER, pageable);

        assertThat(page.content()).hasSize(1);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(7);
        assertThat(page.totalPages()).isEqualTo(4);
    }

    @Test
    @DisplayName("Неизвестный код в фильтре не доходит до базы")
    void unknownFilterCodeNeverReachesDatabase() {
        when(references.getIdOrNull(ReferenceType.CATEGORY, "NOPE"))
                .thenThrow(new IllegalArgumentException("Unknown category code: NOPE"));

        assertThatThrownBy(() -> service.search(
                new ProductFilterRequest("NOPE", null, null, null), PageRequest.of(0, 20)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(products, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("Сортировка по неизвестному полю — ошибка клиента, а не 500 из недр Spring Data")
    void unknownSortPropertyIsRejected() {
        assertThatThrownBy(() -> service.search(
                NO_FILTER, PageRequest.of(0, 20, Sort.by("foo"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("foo");

        verify(products, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("Сортировка по разрешённому полю проходит")
    void allowedSortPropertyPassesThrough() {
        var pageable = PageRequest.of(0, 20, Sort.by("price").descending());
        when(products.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        assertThat(service.search(NO_FILTER, pageable).content()).isEmpty();
    }

    @Test
    @DisplayName("Отсутствующий товар — 404, а не пустой ответ")
    void missingProductIsNotFound() {
        UUID id = UUID.randomUUID();
        when(products.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("Создание переносит поля запроса и резолвит справочники по кодам")
    void createCopiesRequestAndResolvesReferences() {
        referencesResolve();
        when(products.existsBySku("SKU-NEW")).thenReturn(false);
        when(products.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        service.create(request("SKU-NEW"));

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(products).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getSku()).isEqualTo("SKU-NEW");
        assertThat(saved.getValue().getPrice()).isEqualByComparingTo("2499.00");
        assertThat(saved.getValue().isActive()).isTrue();
        verify(categories).getReferenceById(FOOD.id());
        verify(species).getReferenceById(DOG.id());
        verify(brands).getReferenceById(TRIXIE.id());
    }

    @Test
    @DisplayName("Ответ на запись собирается из кеша, а не из ленивых прокси")
    void writeResponseIsBuiltFromCache() {
        referencesResolve();
        when(products.existsBySku("SKU-NEW")).thenReturn(false);
        when(products.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        var created = service.create(request("SKU-NEW"));

        assertThat(created.category().code()).isEqualTo("FOOD");
        assertThat(created.species().name()).isEqualTo("Собаки");
        assertThat(created.brand().code()).isEqualTo("TRIXIE");
    }

    @Test
    @DisplayName("Повторный sku отклоняется до сохранения")
    void duplicateSkuIsRejectedBeforeSave() {
        when(products.existsBySku("SKU-TAKEN")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("SKU-TAKEN")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKU-TAKEN");

        verify(products, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Гонка на sku: нарушение уникального индекса тоже ошибка клиента")
    void uniqueIndexViolationIsReportedAsClientError() {
        referencesResolve();
        when(products.existsBySku("SKU-RACE")).thenReturn(false);
        when(products.saveAndFlush(any())).thenThrow(violation(
                "duplicate key value violates unique constraint \"uq_product_sku\""));

        assertThatThrownBy(() -> service.create(request("SKU-RACE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKU-RACE");
    }

    @Test
    @DisplayName("Чужое нарушение целостности не выдаётся за занятый sku")
    void otherIntegrityViolationIsNotDisguised() {
        referencesResolve();
        when(products.existsBySku("SKU-NEW")).thenReturn(false);
        when(products.saveAndFlush(any()))
                .thenThrow(violation("insert violates foreign key constraint \"fk_product_brand\""));

        assertThatThrownBy(() -> service.create(request("SKU-NEW")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Обновление не считает собственный sku занятым и не спрашивает о нём базу")
    void updateAllowsKeepingOwnSku() {
        UUID id = UUID.randomUUID();
        Product existing = product(id, "SKU-1");
        referencesResolve();
        when(products.findById(id)).thenReturn(Optional.of(existing));
        when(products.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        var updated = service.update(id, request("SKU-1"));

        assertThat(updated.sku()).isEqualTo("SKU-1");
        verify(products).saveAndFlush(existing);
        verify(products, never()).existsBySku(any());
    }

    @Test
    @DisplayName("Обновление без поля active сохраняет текущий признак")
    void updateWithoutActiveKeepsCurrentFlag() {
        UUID id = UUID.randomUUID();
        Product withdrawn = product(id, "SKU-1");
        withdrawn.setActive(false);
        referencesResolve();
        when(products.findById(id)).thenReturn(Optional.of(withdrawn));
        when(products.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        var updated = service.update(id, request("SKU-1"));

        assertThat(updated.active()).isFalse();
        assertThat(withdrawn.isActive()).isFalse();
    }

    @Test
    @DisplayName("Явный active в запросе на обновление применяется")
    void updateAppliesExplicitActiveFlag() {
        UUID id = UUID.randomUUID();
        Product withdrawn = product(id, "SKU-1");
        withdrawn.setActive(false);
        referencesResolve();
        when(products.findById(id)).thenReturn(Optional.of(withdrawn));
        when(products.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        assertThat(service.update(id, request("SKU-1", true)).active()).isTrue();
    }

    @Test
    @DisplayName("Обновление на чужой sku отклоняется")
    void updateRejectsSkuTakenByAnotherProduct() {
        UUID id = UUID.randomUUID();
        when(products.findById(id)).thenReturn(Optional.of(product(id, "SKU-1")));
        when(products.existsBySku("SKU-2")).thenReturn(true);

        assertThatThrownBy(() -> service.update(id, request("SKU-2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKU-2");

        verify(products, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("gRPC-выдача не ходит в базу за пустым списком идентификаторов")
    void emptyIdListSkipsDatabase() {
        assertThat(service.getProductSummaries(List.of())).isEmpty();

        verify(products, never()).findAllByIdIn(any());
    }

    @Test
    @DisplayName("gRPC-выдача отдаёт цену и признак активности")
    void summariesCarryPriceAndActiveFlag() {
        UUID id = UUID.randomUUID();
        when(products.findAllByIdIn(List.of(id))).thenReturn(
                List.of(new ProductSummary(id, "Корм", new BigDecimal("2499.00"), true)));

        assertThat(service.getProductSummaries(List.of(id)))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.id()).isEqualTo(id);
                    assertThat(summary.price()).isEqualByComparingTo("2499.00");
                    assertThat(summary.active()).isTrue();
                });
    }
}
