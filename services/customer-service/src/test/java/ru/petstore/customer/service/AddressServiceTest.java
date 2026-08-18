package ru.petstore.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
import ru.petstore.common.reference.ReferenceDataService;
import ru.petstore.common.reference.ReferenceItem;
import ru.petstore.common.web.ConcurrentChangeException;
import ru.petstore.common.web.ResourceNotFoundException;
import ru.petstore.customer.domain.Address;
import ru.petstore.customer.domain.City;
import ru.petstore.customer.domain.Customer;
import ru.petstore.customer.repository.AddressRepository;
import ru.petstore.customer.repository.CityRepository;
import ru.petstore.customer.repository.CustomerRepository;
import ru.petstore.customer.web.dto.AddressRequest;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AddressServiceTest {

    @Mock
    private AddressRepository addressRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private CityRepository cityRepository;
    @Mock
    private ReferenceDataService referenceDataService;

    @InjectMocks
    private AddressService addressService;

    private static final ReferenceItem MSK = new ReferenceItem(10L, "MSK", "Москва");

    private final UUID customerId = UUID.randomUUID();
    private final Customer customer = customer();

    private Customer customer() {
        var owner = new Customer();
        owner.setId(customerId);
        owner.setEmail("ivan@example.com");
        return owner;
    }

    private static AddressRequest request(Boolean defaultAddress) {
        return new AddressRequest("MSK", "Дмитровское шоссе", "9к3", "154", "127434", defaultAddress);
    }

    private Address address(UUID id, boolean defaultAddress) {
        var address = new Address();
        address.setId(id);
        address.setCustomer(customer);
        address.setDefaultAddress(defaultAddress);
        var city = new City();
        city.setId(MSK.id());
        city.setCode(MSK.code());
        city.setName(MSK.name());
        address.setCity(city);
        return address;
    }

    private void customerAndCityResolve() {
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(referenceDataService.getRequired(ReferenceType.CITY, "MSK")).thenReturn(MSK);
        when(cityRepository.getReferenceById(MSK.id())).thenReturn(new City());
        when(addressRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("Первый адрес клиента становится основным, даже если об этом не просили")
    void firstAddressBecomesDefault() {
        customerAndCityResolve();
        when(addressRepository.existsByCustomerId(customerId)).thenReturn(false);

        var created = addressService.create(customerId, request(null));

        assertThat(created.defaultAddress()).isTrue();
        verify(addressRepository, never()).clearDefault(any(), any());
    }

    @Test
    @DisplayName("Второй адрес без просьбы основным не становится")
    void secondAddressIsNotDefaultByItself() {
        customerAndCityResolve();
        when(addressRepository.existsByCustomerId(customerId)).thenReturn(true);

        var created = addressService.create(customerId, request(null));

        assertThat(created.defaultAddress()).isFalse();
        verify(addressRepository, never()).clearDefault(any(), any());
    }

    @Test
    @DisplayName("Новый основной адрес снимает признак с прежнего до собственной вставки")
    void newDefaultAddressClearsThePreviousOne() {
        customerAndCityResolve();
        when(addressRepository.existsByCustomerId(customerId)).thenReturn(true);

        var created = addressService.create(customerId, request(true));

        assertThat(created.defaultAddress()).isTrue();
        verify(addressRepository).clearDefault(eq(customerId), any(Instant.class));
    }

    @Test
    @DisplayName("Адрес заводится на своего клиента и в запрошенном городе")
    void createdAddressBelongsToCustomerAndCity() {
        customerAndCityResolve();
        when(addressRepository.existsByCustomerId(customerId)).thenReturn(false);

        addressService.create(customerId, request(null));

        ArgumentCaptor<Address> saved = ArgumentCaptor.forClass(Address.class);
        verify(addressRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getCustomer().getId()).isEqualTo(customerId);
        assertThat(saved.getValue().getStreet()).isEqualTo("Дмитровское шоссе");
        assertThat(saved.getValue().getPostalCode()).isEqualTo("127434");
        verify(cityRepository).getReferenceById(MSK.id());
    }

    @Test
    @DisplayName("Адрес несуществующего клиента не заводится")
    void addressOfMissingCustomerIsRejected() {
        when(customerRepository.findById(customerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> addressService.create(customerId, request(null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(customerId.toString());

        verify(addressRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Перевод признака на другой адрес снимает его с прежнего")
    void updateMovesTheDefaultFlag() {
        UUID addressId = UUID.randomUUID();
        customerAndCityResolve();
        when(addressRepository.findByIdAndCustomerId(addressId, customerId))
                .thenReturn(Optional.of(address(addressId, false)));

        var updated = addressService.update(customerId, addressId, request(true));

        assertThat(updated.defaultAddress()).isTrue();
        verify(addressRepository).clearDefault(eq(customerId), any(Instant.class));
    }

    @Test
    @DisplayName("Снять признак основного адреса напрямую нельзя — его переводят на другой адрес")
    void clearingTheDefaultFlagIsRejected() {
        UUID addressId = UUID.randomUUID();
        customerAndCityResolve();
        when(addressRepository.findByIdAndCustomerId(addressId, customerId))
                .thenReturn(Optional.of(address(addressId, true)));

        assertThatThrownBy(() -> addressService.update(customerId, addressId, request(false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(addressId.toString());

        verify(addressRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Обновление без поля defaultAddress сохраняет текущий признак")
    void updateWithoutTheFlagKeepsIt() {
        UUID addressId = UUID.randomUUID();
        customerAndCityResolve();
        when(addressRepository.findByIdAndCustomerId(addressId, customerId))
                .thenReturn(Optional.of(address(addressId, true)));

        assertThat(addressService.update(customerId, addressId, request(null)).defaultAddress()).isTrue();
        verify(addressRepository, never()).clearDefault(any(), any());
    }

    @Test
    @DisplayName("Чужой адрес не обновляется")
    void updateOfAnotherCustomersAddressIsNotFound() {
        UUID addressId = UUID.randomUUID();
        when(addressRepository.findByIdAndCustomerId(addressId, customerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> addressService.update(customerId, addressId, request(null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(addressId.toString());
    }

    @Test
    @DisplayName("Удаление основного адреса передаёт признак старейшему из оставшихся")
    void deletingTheDefaultPromotesTheOldestRemaining() {
        UUID addressId = UUID.randomUUID();
        Address next = address(UUID.randomUUID(), false);
        when(addressRepository.findByIdAndCustomerId(addressId, customerId))
                .thenReturn(Optional.of(address(addressId, true)));
        when(addressRepository.findFirstByCustomerIdOrderByCreatedAtAsc(customerId))
                .thenReturn(Optional.of(next));

        addressService.delete(customerId, addressId);

        assertThat(next.isDefaultAddress()).isTrue();
        verify(addressRepository).saveAndFlush(next);
    }

    @Test
    @DisplayName("Удаление обычного адреса никого не повышает")
    void deletingANonDefaultAddressPromotesNobody() {
        UUID addressId = UUID.randomUUID();
        when(addressRepository.findByIdAndCustomerId(addressId, customerId))
                .thenReturn(Optional.of(address(addressId, false)));

        addressService.delete(customerId, addressId);

        verify(addressRepository).delete(any(Address.class));
        verify(addressRepository, never()).findFirstByCustomerIdOrderByCreatedAtAsc(any());
    }

    @Test
    @DisplayName("Последний адрес удаляется без преемника")
    void deletingTheOnlyAddressLeavesNoDefault() {
        UUID addressId = UUID.randomUUID();
        when(addressRepository.findByIdAndCustomerId(addressId, customerId))
                .thenReturn(Optional.of(address(addressId, true)));
        when(addressRepository.findFirstByCustomerIdOrderByCreatedAtAsc(customerId))
                .thenReturn(Optional.empty());

        addressService.delete(customerId, addressId);

        verify(addressRepository).delete(any(Address.class));
        verify(addressRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Гонка за основным адресом отдаётся как конфликт, а не как 500")
    void raceForTheDefaultFlagIsReportedAsConflict() {
        customerAndCityResolve();
        when(addressRepository.existsByCustomerId(customerId)).thenReturn(true);
        when(addressRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException("duplicate key value violates unique constraint \"uq_address_default\"")));

        assertThatThrownBy(() -> addressService.create(customerId, request(true)))
                .isInstanceOf(ConcurrentChangeException.class)
                .hasMessageContaining(customerId.toString());
    }

    @Test
    @DisplayName("Та же гонка на обновлении не трогает ленивый прокси клиента в упавшей транзакции")
    void raceOnUpdateDoesNotTouchTheLazyCustomerProxy() {
        UUID addressId = UUID.randomUUID();
        Address managed = address(addressId, false);
        // Адрес читается без клиента: getCustomer() здесь — прокси, который в оборванной
        // транзакции инициализировать нечем.
        managed.setCustomer(null);
        customerAndCityResolve();
        when(addressRepository.findByIdAndCustomerId(addressId, customerId))
                .thenReturn(Optional.of(managed));
        when(addressRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException("duplicate key value violates unique constraint \"uq_address_default\"")));

        assertThatThrownBy(() -> addressService.update(customerId, addressId, request(true)))
                .isInstanceOf(ConcurrentChangeException.class)
                .hasMessageContaining(customerId.toString());
    }

    @Test
    @DisplayName("Гонка при повышении преемника тоже конфликт, а не 500")
    void raceWhilePromotingTheSuccessorIsReportedAsConflict() {
        UUID addressId = UUID.randomUUID();
        when(addressRepository.findByIdAndCustomerId(addressId, customerId))
                .thenReturn(Optional.of(address(addressId, true)));
        when(addressRepository.findFirstByCustomerIdOrderByCreatedAtAsc(customerId))
                .thenReturn(Optional.of(address(UUID.randomUUID(), false)));
        when(addressRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException("duplicate key value violates unique constraint \"uq_address_default\"")));

        assertThatThrownBy(() -> addressService.delete(customerId, addressId))
                .isInstanceOf(ConcurrentChangeException.class)
                .hasMessageContaining(customerId.toString());
    }

    @Test
    @DisplayName("Чужое нарушение целостности не выдаётся за гонку")
    void otherIntegrityViolationIsNotDisguised() {
        customerAndCityResolve();
        when(addressRepository.existsByCustomerId(customerId)).thenReturn(false);
        when(addressRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException("insert violates foreign key constraint \"fk_address_city\"")));

        assertThatThrownBy(() -> addressService.create(customerId, request(null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
