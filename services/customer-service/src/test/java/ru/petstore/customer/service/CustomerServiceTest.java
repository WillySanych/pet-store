package ru.petstore.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import ru.petstore.common.reference.ReferenceDataService;
import ru.petstore.common.reference.ReferenceEntity;
import ru.petstore.common.reference.ReferenceItem;
import ru.petstore.common.web.ResourceNotFoundException;
import ru.petstore.customer.domain.Address;
import ru.petstore.customer.domain.City;
import ru.petstore.customer.domain.Customer;
import ru.petstore.customer.domain.CustomerStatus;
import ru.petstore.customer.repository.AddressRepository;
import ru.petstore.customer.repository.CustomerRepository;
import ru.petstore.customer.repository.CustomerStatusRepository;
import ru.petstore.customer.web.dto.CustomerFilterRequest;
import ru.petstore.customer.web.dto.CustomerRequest;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private AddressRepository addressRepository;
    @Mock
    private CustomerStatusRepository customerStatusRepository;
    @Mock
    private ReferenceDataService referenceDataService;

    @InjectMocks
    private CustomerService customerService;

    private static final CustomerFilterRequest NO_FILTER = new CustomerFilterRequest(null, null);

    private static final ReferenceItem NEW = new ReferenceItem(1L, "NEW", "Новый");
    private static final ReferenceItem ACTIVE = new ReferenceItem(2L, "ACTIVE", "Активный");
    private static final ReferenceItem BLOCKED = new ReferenceItem(3L, "BLOCKED", "Заблокирован");
    private static final ReferenceItem MSK = new ReferenceItem(10L, "MSK", "Москва");

    private static CustomerRequest request(String email) {
        return request(email, null);
    }

    private static CustomerRequest request(String email, String statusCode) {
        return new CustomerRequest(email, "+79161234567", "Иван", "Петров", statusCode);
    }

    private static Customer customer(UUID id, String email, ReferenceItem status) {
        var customer = new Customer();
        customer.setId(id);
        customer.setEmail(email);
        customer.setFirstName("Иван");
        customer.setLastName("Петров");
        customer.setStatus(reference(CustomerStatus::new, status));
        return customer;
    }

    private static Address address(UUID id, Customer owner) {
        var address = new Address();
        address.setId(id);
        address.setCustomer(owner);
        address.setCity(reference(City::new, MSK));
        address.setStreet("Дмитровское шоссе");
        address.setBuilding("9к3");
        return address;
    }

    private static <T extends ReferenceEntity> T reference(Supplier<T> factory, ReferenceItem item) {
        T entity = factory.get();
        entity.setId(item.id());
        entity.setCode(item.code());
        entity.setName(item.name());
        return entity;
    }

    private void statusesResolve() {
        when(referenceDataService.getRequired(ReferenceType.CUSTOMER_STATUS, "NEW")).thenReturn(NEW);
        when(referenceDataService.getRequired(ReferenceType.CUSTOMER_STATUS, "ACTIVE")).thenReturn(ACTIVE);
        when(referenceDataService.getRequired(ReferenceType.CUSTOMER_STATUS, "BLOCKED")).thenReturn(BLOCKED);
        when(customerStatusRepository.getReferenceById(any())).thenReturn(new CustomerStatus());
        when(customerRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    private static DataIntegrityViolationException violation(String cause) {
        return new DataIntegrityViolationException("could not execute statement", new SQLException(cause));
    }

    @Test
    @DisplayName("Пустой фильтр не мешает выдаче и отдаёт метаданные страницы")
    void emptyFilterReturnsPageMetadata() {
        var pageable = PageRequest.of(1, 2);
        when(customerRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(customer(UUID.randomUUID(), "a@example.com", ACTIVE)),
                        pageable, 7));

        var page = customerService.search(NO_FILTER, pageable);

        assertThat(page.content()).hasSize(1);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(7);
        assertThat(page.totalPages()).isEqualTo(4);
    }

    @Test
    @DisplayName("Неизвестный код статуса в фильтре не доходит до базы")
    void unknownStatusFilterNeverReachesDatabase() {
        when(referenceDataService.getIdOrNull(ReferenceType.CUSTOMER_STATUS, "NOPE"))
                .thenThrow(new IllegalArgumentException("Unknown customer_status code: NOPE"));

        assertThatThrownBy(() -> customerService.search(
                new CustomerFilterRequest("NOPE", null), PageRequest.of(0, 20)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(customerRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("Сортировка по неизвестному полю — ошибка клиента, а не 500 из недр Spring Data")
    void unknownSortPropertyIsRejected() {
        assertThatThrownBy(() -> customerService.search(NO_FILTER, PageRequest.of(0, 20, Sort.by("foo"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("foo");

        verify(customerRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("Отсутствующий клиент — 404, а не пустой ответ")
    void missingCustomerIsNotFound() {
        UUID id = UUID.randomUUID();
        when(customerRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.get(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("Новый клиент заводится со статусом NEW")
    void createAssignsNewStatus() {
        statusesResolve();
        when(customerRepository.existsByEmail("ivan@example.com")).thenReturn(false);

        var created = customerService.create(request("ivan@example.com"));

        ArgumentCaptor<Customer> saved = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ivan@example.com");
        assertThat(saved.getValue().getFirstName()).isEqualTo("Иван");
        assertThat(created.status().code()).isEqualTo("NEW");
        verify(customerStatusRepository).getReferenceById(NEW.id());
    }

    @Test
    @DisplayName("Явный статус в запросе на создание применяется")
    void createAppliesExplicitStatus() {
        statusesResolve();
        when(customerRepository.existsByEmail("ivan@example.com")).thenReturn(false);

        assertThat(customerService.create(request("ivan@example.com", "ACTIVE")).status().code())
                .isEqualTo("ACTIVE");
        verify(customerStatusRepository).getReferenceById(ACTIVE.id());
    }

    @Test
    @DisplayName("Ответ на запись собирается из кеша, а не из ленивых прокси")
    void writeResponseIsBuiltFromCache() {
        statusesResolve();
        when(customerRepository.existsByEmail("ivan@example.com")).thenReturn(false);

        var created = customerService.create(request("ivan@example.com"));

        assertThat(created.status().name()).isEqualTo("Новый");
    }

    @Test
    @DisplayName("Почта приводится к нижнему регистру: тот же ящик в другом регистре — та же почта")
    void emailIsStoredInLowerCase() {
        statusesResolve();
        when(customerRepository.existsByEmail("ivan@example.com")).thenReturn(false);

        var created = customerService.create(request("  Ivan@Example.COM  "));

        assertThat(created.email()).isEqualTo("ivan@example.com");
        verify(customerRepository).existsByEmail("ivan@example.com");
    }

    @Test
    @DisplayName("Занятый ящик в другом регистре отклоняется")
    void duplicateEmailInAnotherCaseIsRejected() {
        when(customerRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> customerService.create(request("TAKEN@Example.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taken@example.com");

        verify(customerRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Обновление на собственную почту в другом регистре не считает её чужой")
    void updateKeepsOwnEmailRegardlessOfCase() {
        UUID id = UUID.randomUUID();
        statusesResolve();
        when(customerRepository.findById(id))
                .thenReturn(Optional.of(customer(id, "ivan@example.com", ACTIVE)));

        customerService.update(id, request("IVAN@example.com"));

        verify(customerRepository, never()).existsByEmail(any());
    }

    @Test
    @DisplayName("Занятая почта отклоняется до сохранения")
    void duplicateEmailIsRejectedBeforeSave() {
        when(customerRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> customerService.create(request("taken@example.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taken@example.com");

        verify(customerRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Гонка на почте: нарушение уникального индекса тоже ошибка клиента")
    void uniqueIndexViolationIsReportedAsClientError() {
        statusesResolve();
        when(customerRepository.existsByEmail("race@example.com")).thenReturn(false);
        when(customerRepository.saveAndFlush(any())).thenThrow(violation(
                "duplicate key value violates unique constraint \"uq_customer_email\""));

        assertThatThrownBy(() -> customerService.create(request("race@example.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("race@example.com");
    }

    @Test
    @DisplayName("Чужое нарушение целостности не выдаётся за занятую почту")
    void otherIntegrityViolationIsNotDisguised() {
        statusesResolve();
        when(customerRepository.existsByEmail("ivan@example.com")).thenReturn(false);
        when(customerRepository.saveAndFlush(any()))
                .thenThrow(violation("insert violates foreign key constraint \"fk_customer_status\""));

        assertThatThrownBy(() -> customerService.create(request("ivan@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Обновление не считает собственную почту занятой и не спрашивает о ней базу")
    void updateAllowsKeepingOwnEmail() {
        UUID id = UUID.randomUUID();
        statusesResolve();
        when(customerRepository.findById(id))
                .thenReturn(Optional.of(customer(id, "ivan@example.com", ACTIVE)));

        var updated = customerService.update(id, request("ivan@example.com"));

        assertThat(updated.email()).isEqualTo("ivan@example.com");
        verify(customerRepository, never()).existsByEmail(any());
    }

    @Test
    @DisplayName("Обновление без поля statusCode сохраняет текущий статус")
    void updateWithoutStatusKeepsCurrentOne() {
        UUID id = UUID.randomUUID();
        statusesResolve();
        when(customerRepository.findById(id))
                .thenReturn(Optional.of(customer(id, "ivan@example.com", BLOCKED)));

        assertThat(customerService.update(id, request("ivan@example.com")).status().code())
                .isEqualTo("BLOCKED");
    }

    @Test
    @DisplayName("Явный статус в запросе на обновление применяется")
    void updateAppliesExplicitStatus() {
        UUID id = UUID.randomUUID();
        statusesResolve();
        when(customerRepository.findById(id))
                .thenReturn(Optional.of(customer(id, "ivan@example.com", NEW)));

        assertThat(customerService.update(id, request("ivan@example.com", "ACTIVE")).status().code())
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Обновление на чужую почту отклоняется")
    void updateRejectsEmailTakenByAnotherCustomer() {
        UUID id = UUID.randomUUID();
        when(customerRepository.findById(id))
                .thenReturn(Optional.of(customer(id, "ivan@example.com", ACTIVE)));
        when(customerRepository.existsByEmail("maria@example.com")).thenReturn(true);

        assertThatThrownBy(() -> customerService.update(id, request("maria@example.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maria@example.com");

        verify(customerRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Удаление несуществующего клиента — 404, а не тихое ничего")
    void deleteOfMissingCustomerIsNotFound() {
        UUID id = UUID.randomUUID();
        when(customerRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.delete(id))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(customerRepository, never()).delete(any(Customer.class));
    }

    @Test
    @DisplayName("Без addressId адресом доставки становится основной адрес")
    void deliveryTargetFallsBackToDefaultAddress() {
        UUID id = UUID.randomUUID();
        Customer owner = customer(id, "ivan@example.com", ACTIVE);
        when(customerRepository.findById(id)).thenReturn(Optional.of(owner));
        when(addressRepository.findByCustomerIdAndDefaultAddressTrue(id))
                .thenReturn(Optional.of(address(UUID.randomUUID(), owner)));

        var target = customerService.deliveryTarget(id, null);

        assertThat(target.customer().email()).isEqualTo("ivan@example.com");
        assertThat(target.customer().status().code()).isEqualTo("ACTIVE");
        assertThat(target.address().city().code()).isEqualTo("MSK");
        verify(addressRepository, never()).findByIdAndCustomerId(any(), any());
    }

    @Test
    @DisplayName("Названный адрес доставки берётся вместо основного")
    void deliveryTargetUsesRequestedAddress() {
        UUID id = UUID.randomUUID();
        UUID addressId = UUID.randomUUID();
        Customer owner = customer(id, "ivan@example.com", ACTIVE);
        when(customerRepository.findById(id)).thenReturn(Optional.of(owner));
        when(addressRepository.findByIdAndCustomerId(addressId, id))
                .thenReturn(Optional.of(address(addressId, owner)));

        assertThat(customerService.deliveryTarget(id, addressId).address().id()).isEqualTo(addressId);
        verify(addressRepository, never()).findByCustomerIdAndDefaultAddressTrue(any());
    }

    @Test
    @DisplayName("Клиент без адресов — 404, а не заказ в никуда")
    void deliveryTargetWithoutAddressesIsNotFound() {
        UUID id = UUID.randomUUID();
        when(customerRepository.findById(id))
                .thenReturn(Optional.of(customer(id, "ivan@example.com", ACTIVE)));
        when(addressRepository.findByCustomerIdAndDefaultAddressTrue(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.deliveryTarget(id, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("no delivery address");
    }

    @Test
    @DisplayName("Чужой адрес не отдаётся под видом адреса клиента")
    void deliveryTargetRejectsAddressOfAnotherCustomer() {
        UUID id = UUID.randomUUID();
        UUID addressId = UUID.randomUUID();
        when(customerRepository.findById(id))
                .thenReturn(Optional.of(customer(id, "ivan@example.com", ACTIVE)));
        when(addressRepository.findByIdAndCustomerId(addressId, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.deliveryTarget(id, addressId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(addressId.toString());
    }
}
