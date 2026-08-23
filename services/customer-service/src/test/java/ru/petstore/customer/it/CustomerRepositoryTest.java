package ru.petstore.customer.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import ru.petstore.common.reference.ReferenceEntity;
import ru.petstore.customer.domain.Address;
import ru.petstore.customer.domain.City;
import ru.petstore.customer.domain.Customer;
import ru.petstore.customer.domain.CustomerStatus;
import ru.petstore.customer.repository.AddressRepository;
import ru.petstore.customer.repository.CityRepository;
import ru.petstore.customer.repository.CustomerRepository;
import ru.petstore.customer.repository.CustomerStatusRepository;

@SpringBootTest(properties = {
        "spring.liquibase.contexts=test",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@Transactional
class CustomerRepositoryTest extends AbstractPostgresTest {

    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private AddressRepository addressRepository;
    @Autowired
    private CityRepository cityRepository;
    @Autowired
    private CustomerStatusRepository customerStatusRepository;
    @PersistenceContext
    private EntityManager entityManager;

    private CustomerStatus active;
    private City moscow;

    @BeforeEach
    void isolateFromCommittedCustomers() {
        customerRepository.deleteAllInBatch();
        active = byCode(customerStatusRepository, "ACTIVE");
        moscow = byCode(cityRepository, "MSK");
    }

    private static <T extends ReferenceEntity> T byCode(JpaRepository<T, Long> repository, String code) {
        return repository.findAll().stream()
                .filter(entry -> entry.getCode().equals(code)).findFirst().orElseThrow();
    }

    private Statistics detachedWithFreshStatistics() {
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        return statistics;
    }

    private Customer saveCustomer(String email) {
        var customer = new Customer();
        customer.setEmail(email);
        customer.setFirstName("Иван");
        customer.setLastName("Петров");
        customer.setStatus(active);
        return customerRepository.saveAndFlush(customer);
    }

    private Address saveAddress(Customer customer, String street, boolean defaultAddress) {
        var address = new Address();
        address.setCustomer(customer);
        address.setCity(moscow);
        address.setStreet(street);
        address.setBuilding("1");
        address.setDefaultAddress(defaultAddress);
        return addressRepository.saveAndFlush(address);
    }

    @Test
    @DisplayName("Миграции создают схему и наполняют справочники")
    void migrationsCreateSchemaAndSeedReferenceTables() {
        assertThat(cityRepository.findAll()).extracting(City::getCode)
                .contains("MSK", "SPB", "EKB", "NSK", "KZN", "NN");
        assertThat(customerStatusRepository.findAll()).extracting(CustomerStatus::getCode)
                .containsExactlyInAnyOrder("NEW", "ACTIVE", "BLOCKED");
    }

    @Test
    @DisplayName("Идентификатор клиента генерируется приложением, а не базой")
    void customerIdIsAssignedByApplication() {
        var saved = saveCustomer("ivan@example.com");

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Занятая почта не сохраняется")
    void duplicateEmailIsRejectedByTheDatabase() {
        saveCustomer("taken@example.com");

        assertThatThrownBy(() -> saveCustomer("taken@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Второй основной адрес одного клиента отбивается частичным уникальным индексом")
    void oneDefaultAddressPerCustomerIsEnforced() {
        var customer = saveCustomer("ivan@example.com");
        saveAddress(customer, "Дмитровское шоссе", true);

        assertThatThrownBy(() -> saveAddress(customer, "Ленинский проспект", true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Индекс не мешает обычным адресам и основным адресам разных клиентов")
    void theIndexAllowsOneDefaultPerCustomerAndAnyNumberOfOthers() {
        var ivan = saveCustomer("ivan@example.com");
        var maria = saveCustomer("maria@example.com");
        saveAddress(ivan, "Дмитровское шоссе", true);
        saveAddress(ivan, "Ленинский проспект", false);
        saveAddress(ivan, "Тверская", false);
        saveAddress(maria, "Пулковское шоссе", true);

        assertThat(addressRepository.findAllByCustomerIdOrderByCreatedAtAsc(ivan.getId())).hasSize(3);
        assertThat(addressRepository.findByCustomerIdAndDefaultAddressTrue(ivan.getId()))
                .get().extracting(Address::getStreet).isEqualTo("Дмитровское шоссе");
        assertThat(addressRepository.findByCustomerIdAndDefaultAddressTrue(maria.getId())).isPresent();
    }

    @Test
    @DisplayName("Снятие признака у прежнего основного адреса делается одним запросом")
    void clearDefaultTouchesOnlyTheDefaultAddressOfOneCustomer() {
        var ivan = saveCustomer("ivan@example.com");
        var maria = saveCustomer("maria@example.com");
        saveAddress(ivan, "Дмитровское шоссе", true);
        saveAddress(maria, "Пулковское шоссе", true);

        assertThat(addressRepository.clearDefault(ivan.getId(), Instant.now())).isEqualTo(1);
        entityManager.clear();

        assertThat(addressRepository.findByCustomerIdAndDefaultAddressTrue(ivan.getId())).isEmpty();
        assertThat(addressRepository.findByCustomerIdAndDefaultAddressTrue(maria.getId())).isPresent();
    }

    @Test
    @DisplayName("Удаление клиента уносит его адреса")
    void removingACustomerCascadesToAddresses() {
        var customer = saveCustomer("ivan@example.com");
        saveAddress(customer, "Дмитровское шоссе", true);
        UUID customerId = customer.getId();
        // Как на рабочем пути: сервис удаляет клиента, не держа его адреса в сессии.
        detachedWithFreshStatistics();

        customerRepository.delete(customerRepository.findById(customerId).orElseThrow());
        customerRepository.flush();

        assertThat(addressRepository.findAllByCustomerIdOrderByCreatedAtAsc(customerId)).isEmpty();
    }

    @Test
    @DisplayName("Чтение клиента подтягивает статус одним запросом")
    void findByIdFetchesStatus() {
        var saved = saveCustomer("ivan@example.com");
        var statistics = detachedWithFreshStatistics();

        var found = customerRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getStatus().getCode()).isEqualTo("ACTIVE");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Список адресов не превращается в N+1 по городам")
    void addressListingFetchesCitiesWithoutNPlusOne() {
        var customer = saveCustomer("ivan@example.com");
        saveAddress(customer, "Дмитровское шоссе", true);
        saveAddress(customer, "Ленинский проспект", false);
        saveAddress(customer, "Тверская", false);
        var statistics = detachedWithFreshStatistics();

        List<Address> addresses = addressRepository.findAllByCustomerIdOrderByCreatedAtAsc(customer.getId());

        assertThat(addresses).hasSize(3);
        addresses.forEach(address -> assertThat(address.getCity().getCode()).isEqualTo("MSK"));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Чужой адрес по паре идентификаторов не находится")
    void addressOfAnotherCustomerIsNotFoundByPair() {
        var ivan = saveCustomer("ivan@example.com");
        var maria = saveCustomer("maria@example.com");
        var address = saveAddress(ivan, "Дмитровское шоссе", true);

        assertThat(addressRepository.findByIdAndCustomerId(address.getId(), maria.getId())).isEmpty();
        assertThat(addressRepository.findByIdAndCustomerId(address.getId(), ivan.getId())).isPresent();
        assertThat(addressRepository.findByIdAndCustomerId(UUID.randomUUID(), ivan.getId())).isEmpty();
    }
}
