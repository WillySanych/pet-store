package ru.petstore.customer.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
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
import ru.petstore.common.web.PageResponse;
import ru.petstore.common.web.ReferenceResponse;
import ru.petstore.customer.web.dto.AddressRequest;
import ru.petstore.customer.web.dto.AddressResponse;
import ru.petstore.customer.web.dto.CustomerRequest;
import ru.petstore.customer.web.dto.CustomerResponse;
import ru.petstore.customer.web.dto.DeliveryTargetResponse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.liquibase.contexts=test")
@AutoConfigureObservability
class CustomerApiTest extends AbstractPostgresTest {

    @Autowired
    private TestRestTemplate testRestTemplate;

    private static String uniqueEmail() {
        return "customer-" + UUID.randomUUID() + "@example.com";
    }

    private static CustomerRequest request(String email) {
        return request(email, "Иван", "Петров", null);
    }

    private static CustomerRequest request(String email, String firstName, String lastName, String status) {
        return new CustomerRequest(email, "+79161234567", firstName, lastName, status);
    }

    private static AddressRequest addressRequest(String street, Boolean defaultAddress) {
        return new AddressRequest("MSK", street, "9к3", "154", "127434", defaultAddress);
    }

    private CustomerResponse createCustomer(CustomerRequest request) {
        var response = testRestTemplate.postForEntity("/api/v1/customers", request, CustomerResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        return response.getBody();
    }

    private CustomerResponse createCustomer() {
        return createCustomer(request(uniqueEmail()));
    }

    private AddressResponse createAddress(UUID customerId, AddressRequest request) {
        var response = testRestTemplate.postForEntity("/api/v1/customers/" + customerId + "/addresses",
                request, AddressResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private List<AddressResponse> addresses(UUID customerId) {
        return testRestTemplate.exchange("/api/v1/customers/" + customerId + "/addresses",
                HttpMethod.GET, null, new ParameterizedTypeReference<List<AddressResponse>>() {
                }).getBody();
    }

    private PageResponse<CustomerResponse> list(String query) {
        return testRestTemplate.exchange("/api/v1/customers" + query, HttpMethod.GET, null,
                new ParameterizedTypeReference<PageResponse<CustomerResponse>>() {
                }).getBody();
    }

    @Test
    @DisplayName("Справочники отдаются из кеша отсортированными по коду")
    void referenceTablesAreServedFromCache() {
        var cities = testRestTemplate.exchange("/api/v1/cities", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<ReferenceResponse>>() {
                }).getBody();

        assertThat(cities).extracting(ReferenceResponse::code)
                .containsExactly("EKB", "KZN", "MSK", "NN", "NSK", "SPB");
    }

    @Test
    @DisplayName("Справочник не отдаёт наружу внутренний идентификатор")
    void referenceTablesDoNotExposeInternalIds() {
        var statuses = testRestTemplate.getForObject("/api/v1/customer-statuses", JsonNode.class);

        assertThat(statuses.get(0).has("id")).isFalse();
        assertThat(statuses.get(0).get("code").asText()).isNotBlank();
    }

    @Test
    @DisplayName("Заведённый клиент доступен по своему идентификатору и начинает с NEW")
    void createdCustomerIsReadableById() {
        var created = createCustomer();

        var found = testRestTemplate.getForObject("/api/v1/customers/" + created.id(),
                CustomerResponse.class);

        assertThat(found.id()).isEqualTo(created.id());
        assertThat(found.email()).isEqualTo(created.email());
        assertThat(found.status().code()).isEqualTo("NEW");
    }

    @Test
    @DisplayName("Фильтр по статусу и поиск по имени отбирают клиентов")
    void listIsFilteredByStatusAndSearch() {
        String surname = "Blocked" + UUID.randomUUID().toString().substring(0, 8);
        createCustomer(request(uniqueEmail(), "Petr", surname, "BLOCKED"));

        var page = list("?status=BLOCKED&search=" + surname + "&size=50");

        assertThat(page.content()).singleElement()
                .satisfies(customer -> assertThat(customer.lastName()).isEqualTo(surname));
    }

    @Test
    @DisplayName("Знак процента в поиске ищется буквально, а не как «всё подряд»")
    void searchTreatsWildcardsAsLiterals() {
        createCustomer();

        var page = list("?search=%25&size=50");

        assertThat(page.totalElements()).isZero();
    }

    @Test
    @DisplayName("Подчёркивание в поиске тоже буквальное")
    void searchTreatsUnderscoreAsLiteral() {
        String surname = "Under" + UUID.randomUUID().toString().substring(0, 8);
        createCustomer(request(uniqueEmail(), "Petr", surname, null));

        var underscored = list("?search=" + surname.substring(0, 5) + "_&size=50");
        var literal = list("?search=" + surname + "&size=50");

        assertThat(underscored.totalElements()).isZero();
        assertThat(literal.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("Тот же ящик в другом регистре вторым клиентом не заводится")
    void sameMailboxInAnotherCaseIsRejected() {
        var email = uniqueEmail();
        var created = createCustomer(request(email));

        var response = testRestTemplate.postForEntity("/api/v1/customers",
                request(email.toUpperCase(java.util.Locale.ROOT)), JsonNode.class);

        assertThat(created.email()).isEqualTo(email);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message").asText()).contains(email);
    }

    @Test
    @DisplayName("Пагинация отдаёт метаданные страницы")
    void listReturnsPageMetadata() {
        createCustomer();

        var page = list("?size=1&page=0");

        assertThat(page.size()).isEqualTo(1);
        assertThat(page.page()).isZero();
        assertThat(page.content()).hasSize(1);
        assertThat(page.totalElements()).isPositive();
    }

    @Test
    @DisplayName("Сортировка по неизвестному полю — 400, а не 500")
    void unknownSortPropertyReturns400() {
        var response = testRestTemplate.getForEntity("/api/v1/customers?sort=foo", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().get("message").asText()).contains("foo");
    }

    @Test
    @DisplayName("Неизвестный код статуса — 400 в общем формате")
    void unknownStatusCodeReturns400() {
        var response = testRestTemplate.getForEntity("/api/v1/customers?status=NOPE", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().get("requestId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("Невалидное тело — 400 с перечислением полей")
    void invalidBodyReturns400WithFieldList() {
        var invalid = new CustomerRequest("не почта", "телефон", "", "", null);

        var response = testRestTemplate.postForEntity("/api/v1/customers", invalid, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().get("message").asText()).contains("email").contains("phone");
    }

    @Test
    @DisplayName("Занятая почта отклоняется")
    void duplicateEmailIsRejected() {
        var email = uniqueEmail();
        createCustomer(request(email));

        var response = testRestTemplate.postForEntity("/api/v1/customers",
                request(email, "Мария", "Иванова", null), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message").asText()).contains(email);
    }

    @Test
    @DisplayName("Отсутствующий клиент — 404 в общем формате")
    void missingCustomerReturns404() {
        var response = testRestTemplate.getForEntity("/api/v1/customers/" + UUID.randomUUID(),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("PUT заменяет клиента целиком")
    void putReplacesCustomer() {
        var created = createCustomer();

        var updated = testRestTemplate.exchange("/api/v1/customers/" + created.id(), HttpMethod.PUT,
                new HttpEntity<>(request(created.email(), "Мария", "Иванова", "ACTIVE")),
                CustomerResponse.class);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().firstName()).isEqualTo("Мария");
        assertThat(updated.getBody().status().code()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Первый адрес клиента становится основным, второй — по запросу")
    void theDefaultAddressMovesOnRequest() {
        var customer = createCustomer();

        var first = createAddress(customer.id(), addressRequest("Дмитровское шоссе", null));
        var second = createAddress(customer.id(), addressRequest("Ленинский проспект", true));

        assertThat(first.defaultAddress()).isTrue();
        assertThat(second.defaultAddress()).isTrue();
        assertThat(addresses(customer.id()))
                .filteredOn(AddressResponse::defaultAddress)
                .singleElement()
                .extracting(AddressResponse::id).isEqualTo(second.id());
    }

    @Test
    @DisplayName("Снять признак основного адреса напрямую нельзя")
    void clearingTheDefaultFlagReturns400() {
        var customer = createCustomer();
        var address = createAddress(customer.id(), addressRequest("Дмитровское шоссе", null));

        var response = testRestTemplate.exchange(
                "/api/v1/customers/" + customer.id() + "/addresses/" + address.id(), HttpMethod.PUT,
                new HttpEntity<>(addressRequest("Дмитровское шоссе", false)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("BAD_REQUEST");
    }

    @Test
    @DisplayName("Удаление основного адреса передаёт признак оставшемуся")
    void deletingTheDefaultAddressPromotesTheNextOne() {
        var customer = createCustomer();
        var first = createAddress(customer.id(), addressRequest("Дмитровское шоссе", null));
        var second = createAddress(customer.id(), addressRequest("Ленинский проспект", null));

        var deleted = testRestTemplate.exchange(
                "/api/v1/customers/" + customer.id() + "/addresses/" + first.id(),
                HttpMethod.DELETE, null, Void.class);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(addresses(customer.id())).singleElement()
                .satisfies(address -> {
                    assertThat(address.id()).isEqualTo(second.id());
                    assertThat(address.defaultAddress()).isTrue();
                });
    }

    @Test
    @DisplayName("Адрес с неизвестным городом не заводится")
    void unknownCityCodeReturns400() {
        var customer = createCustomer();

        var response = testRestTemplate.postForEntity("/api/v1/customers/" + customer.id() + "/addresses",
                new AddressRequest("NOPE", "Улица", "1", null, null, null), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message").asText()).contains("NOPE");
    }

    @Test
    @DisplayName("order-service получает клиента и адрес доставки одним запросом")
    void deliveryTargetReturnsCustomerAndDefaultAddress() {
        var customer = createCustomer();
        createAddress(customer.id(), addressRequest("Дмитровское шоссе", null));
        var second = createAddress(customer.id(), addressRequest("Ленинский проспект", true));

        var target = testRestTemplate.getForObject(
                "/api/v1/customers/" + customer.id() + "/delivery-target", DeliveryTargetResponse.class);

        assertThat(target.customer().id()).isEqualTo(customer.id());
        assertThat(target.customer().status().code()).isEqualTo("NEW");
        assertThat(target.address().id()).isEqualTo(second.id());
        assertThat(target.address().city().code()).isEqualTo("MSK");
    }

    @Test
    @DisplayName("Названный адрес доставки берётся вместо основного")
    void deliveryTargetAcceptsAnExplicitAddress() {
        var customer = createCustomer();
        var first = createAddress(customer.id(), addressRequest("Дмитровское шоссе", null));
        createAddress(customer.id(), addressRequest("Ленинский проспект", true));

        var target = testRestTemplate.getForObject("/api/v1/customers/" + customer.id()
                + "/delivery-target?addressId=" + first.id(), DeliveryTargetResponse.class);

        assertThat(target.address().id()).isEqualTo(first.id());
        assertThat(target.address().street()).isEqualTo("Дмитровское шоссе");
    }

    @Test
    @DisplayName("Клиент без адресов — 404: заказу некуда ехать")
    void deliveryTargetWithoutAddressesReturns404() {
        var customer = createCustomer();

        var response = testRestTemplate.getForEntity(
                "/api/v1/customers/" + customer.id() + "/delivery-target", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("message").asText()).contains("no delivery address");
    }

    @Test
    @DisplayName("Удаление клиента уносит его адреса")
    void deletingACustomerRemovesAddresses() {
        var customer = createCustomer();
        createAddress(customer.id(), addressRequest("Дмитровское шоссе", null));

        var deleted = testRestTemplate.exchange("/api/v1/customers/" + customer.id(),
                HttpMethod.DELETE, null, Void.class);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(testRestTemplate.getForEntity("/api/v1/customers/" + customer.id(), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(testRestTemplate.getForEntity("/api/v1/customers/" + customer.id() + "/addresses",
                JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Фильтры описаны в OpenAPI по одному параметру, а не вложенным объектом")
    void filtersAreDocumentedAsSeparateParameters() {
        var parameters = testRestTemplate.getForObject("/v3/api-docs", JsonNode.class)
                .at("/paths/~1api~1v1~1customers/get/parameters");

        assertThat(parameters).extracting(parameter -> parameter.get("name").asText())
                .contains("status", "search");
    }

    @Test
    @DisplayName("Размеры кешей уезжают в Prometheus")
    void cacheMetricsAreExported() {
        testRestTemplate.getForObject("/api/v1/cities", String.class);

        var metrics = testRestTemplate.getForObject("/actuator/prometheus", String.class);

        assertThat(metrics)
                .contains("petstore_cache_size")
                .contains("cache=\"cities\"")
                .contains("cache=\"customer-statuses\"");
    }
}
