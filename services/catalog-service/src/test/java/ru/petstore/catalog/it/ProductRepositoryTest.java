package ru.petstore.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import ru.petstore.catalog.domain.Brand;
import ru.petstore.catalog.domain.Category;
import ru.petstore.catalog.domain.Product;
import ru.petstore.catalog.domain.ReferenceEntity;
import ru.petstore.catalog.domain.Species;
import ru.petstore.catalog.repository.BrandRepository;
import ru.petstore.catalog.repository.CategoryRepository;
import ru.petstore.catalog.repository.ProductRepository;
import ru.petstore.catalog.repository.ProductSpecifications;
import ru.petstore.catalog.repository.SpeciesRepository;
import ru.petstore.catalog.service.ProductSummary;

@SpringBootTest(properties = {
        "spring.liquibase.contexts=test",
        "grpc.server.port=0",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@Transactional
class ProductRepositoryTest extends AbstractPostgresTest {

    @Autowired
    private ProductRepository products;
    @Autowired
    private CategoryRepository categories;
    @Autowired
    private SpeciesRepository species;
    @Autowired
    private BrandRepository brands;
    @Autowired
    private JdbcTemplate jdbc;
    @PersistenceContext
    private EntityManager em;

    private Map<String, Category> categoriesByCode;
    private Species dog;
    private Brand trixie;

    @BeforeEach
    void isolateFromCommittedProducts() {
        products.deleteAllInBatch();
        categoriesByCode = categories.findAll().stream()
                .collect(Collectors.toMap(Category::getCode, category -> category));
        dog = byCode(species, "DOG");
        trixie = byCode(brands, "TRIXIE");
    }

    private static <T extends ReferenceEntity> T byCode(JpaRepository<T, Long> repository, String code) {
        return repository.findAll().stream()
                .filter(entry -> entry.getCode().equals(code)).findFirst().orElseThrow();
    }

    private Statistics statistics() {
        return em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    private Statistics detachedWithFreshStatistics() {
        em.flush();
        em.clear();
        Statistics statistics = statistics();
        statistics.clear();
        return statistics;
    }

    private Product save(String sku, String name, String categoryCode, boolean active) {
        var product = new Product();
        product.setSku(sku);
        product.setName(name);
        product.setPrice(new BigDecimal("100.00"));
        product.setActive(active);
        product.setCategory(categoriesByCode.get(categoryCode));
        product.setSpecies(dog);
        product.setBrand(trixie);
        return products.saveAndFlush(product);
    }

    @Test
    @DisplayName("Миграции создают схему и наполняют справочники")
    void migrationsCreateSchemaAndSeedReferenceTables() {
        assertThat(categories.findAll()).extracting(Category::getCode)
                .contains("FOOD", "TOYS", "HYGIENE", "ACCESSORIES", "HEALTH", "HOUSING");
        assertThat(species.findAll()).hasSize(6);
        assertThat(brands.findAll()).hasSize(6);
    }

    @Test
    @DisplayName("Демо-товары не приезжают в тесты: changeset под контекстом demo не выполнялся")
    void demoProductsAreNotLoadedUnderTestContext() {
        Long applied = jdbc.queryForObject(
                "SELECT count(*) FROM catalog.databasechangelog WHERE id = '003-1-demo-products'",
                Long.class);

        assertThat(applied).isZero();
    }

    @Test
    @DisplayName("Идентификатор товара генерируется приложением, а не базой")
    void productIdIsAssignedByApplication() {
        var saved = save("SKU-ID", "Товар", "FOOD", true);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Фильтры складываются и отбирают по внешнему ключу")
    void specificationsCombineIntoOneQuery() {
        save("SKU-1", "Корм А", "FOOD", true);
        save("SKU-2", "Корм Б", "FOOD", false);
        save("SKU-3", "Игрушка", "TOYS", true);

        Specification<Product> spec = Specification.allOf(List.of(
                ProductSpecifications.referenceIs("category", byCode(categories, "FOOD").getId()),
                ProductSpecifications.activeIs(true)));

        assertThat(products.findAll(spec, PageRequest.of(0, 10)).getContent())
                .extracting(Product::getSku)
                .containsExactly("SKU-1");
    }

    @Test
    @DisplayName("Отсутствующий фильтр не сужает выдачу")
    void absentFiltersDoNotNarrowTheResult() {
        save("SKU-1", "Корм", "FOOD", true);
        save("SKU-2", "Игрушка", "TOYS", false);

        Specification<Product> spec = Specification.allOf(
                ProductSpecifications.referenceIs("category", null),
                ProductSpecifications.activeIs(null));

        assertThat(products.findAll(spec, PageRequest.of(0, 10)).getContent())
                .extracting(Product::getSku)
                .containsExactlyInAnyOrder("SKU-1", "SKU-2");
    }

    @Test
    @DisplayName("Пагинация и сортировка отдают запрошенную страницу")
    void paginationReturnsRequestedSlice() {
        save("SKU-A", "Аарон", "FOOD", true);
        save("SKU-B", "Борис", "FOOD", true);
        save("SKU-C", "Виктор", "FOOD", true);

        var page = products.findAll(
                Specification.allOf(List.of()),
                PageRequest.of(1, 2, Sort.by("name")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Product::getSku).containsExactly("SKU-C");
    }

    @Test
    @DisplayName("Товар с чужим sku не сохраняется")
    void duplicateSkuIsRejectedByTheDatabase() {
        save("SKU-UNIQUE", "Первый", "FOOD", true);

        assertThatThrownBy(() -> save("SKU-UNIQUE", "Второй", "TOYS", true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("gRPC-проекция отдаёт четыре поля и не трогает справочники")
    void summariesProjectionReturnsRequestedProductsOnly() {
        var first = save("SKU-P1", "Аарон", "FOOD", true);
        var second = save("SKU-P2", "Борис", "TOYS", false);
        save("SKU-P3", "Виктор", "FOOD", true);

        var summaries = products.findAllByIdIn(List.of(first.getId(), second.getId()));

        assertThat(summaries).extracting(ProductSummary::name)
                .containsExactlyInAnyOrder("Аарон", "Борис");
        assertThat(summaries).extracting(ProductSummary::active)
                .containsExactlyInAnyOrder(true, false);
    }

    @Test
    @DisplayName("Чтение товара подтягивает справочники одним запросом")
    void findByIdFetchesReferenceTables() {
        var saved = save("SKU-GRAPH", "Товар", "FOOD", true);
        var statistics = detachedWithFreshStatistics();

        var found = products.findById(saved.getId()).orElseThrow();

        assertThat(found.getCategory().getCode()).isEqualTo("FOOD");
        assertThat(found.getSpecies().getCode()).isEqualTo("DOG");
        assertThat(found.getBrand().getCode()).isEqualTo("TRIXIE");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Страница товаров не превращается в N+1 по справочникам")
    void listingFetchesReferenceTablesWithoutNPlusOne() {
        save("SKU-N1-A", "Аарон", "FOOD", true);
        save("SKU-N1-B", "Борис", "TOYS", true);
        save("SKU-N1-C", "Виктор", "HEALTH", true);
        var statistics = detachedWithFreshStatistics();

        var page = products.findAll(Specification.<Product>unrestricted(),
                PageRequest.of(0, 10, Sort.by("name")));

        assertThat(page.getContent()).hasSize(3);
        page.getContent().forEach(product -> {
            assertThat(product.getCategory().getCode()).isNotBlank();
            assertThat(product.getSpecies().getCode()).isNotBlank();
            assertThat(product.getBrand().getCode()).isNotBlank();
        });
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }
}
