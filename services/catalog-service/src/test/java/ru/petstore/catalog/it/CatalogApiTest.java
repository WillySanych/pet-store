package ru.petstore.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import ru.petstore.catalog.web.dto.ProductRequest;
import ru.petstore.catalog.web.dto.ProductResponse;
import ru.petstore.common.web.PageResponse;
import ru.petstore.common.web.ReferenceResponse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.liquibase.contexts=test",
        "grpc.server.port=0"
})
@AutoConfigureObservability
class CatalogApiTest extends AbstractPostgresTest {

    @Autowired
    private TestRestTemplate testRestTemplate;

    private static ProductRequest request(String sku, String name,
                                          String categoryCode, String speciesCode) {
        return new ProductRequest(sku, name, "Описание", new BigDecimal("1234.50"),
                categoryCode, speciesCode, "TRIXIE", null);
    }

    private ProductResponse create(ProductRequest request) {
        var response = testRestTemplate.postForEntity("/api/v1/products", request, ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        return response.getBody();
    }

    private ProductResponse put(UUID id, ProductRequest request) {
        var response = testRestTemplate.exchange("/api/v1/products/" + id, HttpMethod.PUT,
                new HttpEntity<>(request), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private PageResponse<ProductResponse> list(String query) {
        return testRestTemplate.exchange("/api/v1/products" + query, HttpMethod.GET, null,
                new ParameterizedTypeReference<PageResponse<ProductResponse>>() {
                }).getBody();
    }

    @Test
    @DisplayName("Справочники отдаются из кеша отсортированными по коду")
    void referenceTablesAreServedFromCache() {
        var categories = testRestTemplate.exchange("/api/v1/categories", HttpMethod.GET, null,
                new ParameterizedTypeReference<java.util.List<ReferenceResponse>>() {
                }).getBody();

        assertThat(categories).extracting(ReferenceResponse::code)
                .containsExactly("ACCESSORIES", "FOOD", "HEALTH", "HOUSING", "HYGIENE", "TOYS");
    }

    @Test
    @DisplayName("Справочник не отдаёт наружу внутренний идентификатор")
    void referenceTablesDoNotExposeInternalIds() {
        var categories = testRestTemplate.getForObject("/api/v1/categories", JsonNode.class);

        assertThat(categories.get(0).has("id")).isFalse();
        assertThat(categories.get(0).get("code").asText()).isNotBlank();
    }

    @Test
    @DisplayName("Созданный товар доступен по своему идентификатору")
    void createdProductIsReadableById() {
        var created = create(request("SKU-" + UUID.randomUUID(), "Новый корм", "FOOD", "DOG"));

        var found = testRestTemplate.getForObject("/api/v1/products/" + created.id(), ProductResponse.class);

        assertThat(found.id()).isEqualTo(created.id());
        assertThat(found.price()).isEqualByComparingTo("1234.50");
        assertThat(found.category().code()).isEqualTo("FOOD");
        assertThat(found.species().code()).isEqualTo("DOG");
        assertThat(found.active()).isTrue();
    }

    @Test
    @DisplayName("Фильтр по коду справочника отбирает товары")
    void listIsFilteredByReferenceCode() {
        var sku = "SKU-" + UUID.randomUUID();
        create(request(sku, "Клетка", "HOUSING", "BIRD"));

        var page = list("?category=HOUSING&species=BIRD&size=50");

        assertThat(page.content()).extracting(ProductResponse::sku).contains(sku);
        assertThat(page.content()).allSatisfy(product ->
                assertThat(product.category().code()).isEqualTo("HOUSING"));
    }

    @Test
    @DisplayName("Пагинация отдаёт метаданные страницы")
    void listReturnsPageMetadata() {
        create(request("SKU-" + UUID.randomUUID(), "Товар", "HEALTH", "CAT"));

        var page = list("?size=1&page=0");

        assertThat(page.size()).isEqualTo(1);
        assertThat(page.page()).isZero();
        assertThat(page.content()).hasSize(1);
        assertThat(page.totalElements()).isPositive();
    }

    @Test
    @DisplayName("Неизвестный код справочника — 400 в общем формате")
    void unknownReferenceCodeReturns400() {
        var response = testRestTemplate.getForEntity("/api/v1/products?category=NOPE", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().get("requestId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("Фильтры описаны в OpenAPI по одному параметру, а не вложенным объектом")
    void filtersAreDocumentedAsSeparateParameters() {
        var parameters = testRestTemplate.getForObject("/v3/api-docs", JsonNode.class)
                .at("/paths/~1api~1v1~1products/get/parameters");

        assertThat(parameters).extracting(parameter -> parameter.get("name").asText())
                .contains("category", "species", "brand", "active");
    }

    @Test
    @DisplayName("Название и версия API берутся из @OpenAPIDefinition")
    void apiDocsCarryServiceInfo() {
        var info = testRestTemplate.getForObject("/v3/api-docs", JsonNode.class).path("info");

        assertThat(info.path("title").asText()).isEqualTo("catalog-service API");
        assertThat(info.path("version").asText()).isEqualTo("v1");
    }

    @Test
    @DisplayName("Нечитаемое значение фильтра — 400 в общем формате")
    void malformedFilterValueReturns400() {
        var response = testRestTemplate.getForEntity("/api/v1/products?active=maybe", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().get("requestId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("Отсутствующий товар — 404 в общем формате")
    void missingProductReturns404() {
        var response = testRestTemplate.getForEntity("/api/v1/products/" + UUID.randomUUID(), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("Невалидное тело — 400 с перечислением полей")
    void invalidBodyReturns400WithFieldList() {
        var invalid = new ProductRequest("", "", null, null, "FOOD", "DOG", "TRIXIE", null);

        var response = testRestTemplate.postForEntity("/api/v1/products", invalid, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().get("message").asText()).contains("sku");
    }

    @Test
    @DisplayName("Повторный sku отклоняется")
    void duplicateSkuIsRejected() {
        var sku = "SKU-" + UUID.randomUUID();
        create(request(sku, "Первый", "FOOD", "CAT"));

        var response = testRestTemplate.postForEntity("/api/v1/products",
                request(sku, "Второй", "TOYS", "CAT"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message").asText()).contains(sku);
    }

    @Test
    @DisplayName("PUT заменяет товар целиком")
    void putReplacesProduct() {
        var created = create(request("SKU-" + UUID.randomUUID(), "Было", "FOOD", "DOG"));
        var replacement = new ProductRequest(created.sku(), "Стало", "Новое описание",
                new BigDecimal("999.99"), "TOYS", "CAT", "PETSTORE", false);

        put(created.id(), replacement);

        var found = testRestTemplate.getForObject("/api/v1/products/" + created.id(), ProductResponse.class);
        assertThat(found.name()).isEqualTo("Стало");
        assertThat(found.price()).isEqualByComparingTo("999.99");
        assertThat(found.category().code()).isEqualTo("TOYS");
        assertThat(found.active()).isFalse();
    }

    @Test
    @DisplayName("PUT без поля active не возвращает снятый товар в продажу")
    void putWithoutActiveKeepsProductWithdrawn() {
        var created = create(request("SKU-" + UUID.randomUUID(), "Лампа", "ACCESSORIES", "REPTILE"));
        var sku = created.sku();
        put(created.id(), new ProductRequest(sku, "Лампа", null,
                created.price(), "ACCESSORIES", "REPTILE", "TRIXIE", false));

        put(created.id(), new ProductRequest(sku, "Лампа для террариума",
                null, created.price(), "ACCESSORIES", "REPTILE", "TRIXIE", null));

        var found = testRestTemplate.getForObject("/api/v1/products/" + created.id(), ProductResponse.class);
        assertThat(found.name()).isEqualTo("Лампа для террариума");
        assertThat(found.active()).isFalse();
    }

    @Test
    @DisplayName("Сортировка по неизвестному полю — 400, а не 500")
    void unknownSortPropertyReturns400() {
        var response = testRestTemplate.getForEntity("/api/v1/products?sort=foo", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().get("message").asText()).contains("foo");
    }

    @Test
    @DisplayName("Readiness поднимается только с прогретыми кешами")
    void readinessReportsWarmedUpCaches() {
        var response = testRestTemplate.getForEntity("/actuator/health/readiness", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName("Размеры кешей каталога уезжают в Prometheus")
    void cacheMetricsAreExported() {
        testRestTemplate.getForObject("/api/v1/categories", String.class);

        var metrics = testRestTemplate.getForObject("/actuator/prometheus", String.class);

        assertThat(metrics)
                .contains("petstore_cache_size")
                .contains("cache=\"categories\"")
                .contains("cache=\"species\"")
                .contains("cache=\"brands\"");
    }
}
