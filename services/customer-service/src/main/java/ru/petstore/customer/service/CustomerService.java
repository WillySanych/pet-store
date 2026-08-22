package ru.petstore.customer.service;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.petstore.common.reference.ReferenceDataService;
import ru.petstore.common.reference.ReferenceItem;
import ru.petstore.common.web.PageResponse;
import ru.petstore.common.web.ReferenceResponse;
import ru.petstore.common.web.ResourceNotFoundException;
import ru.petstore.customer.domain.Address;
import ru.petstore.customer.domain.Customer;
import ru.petstore.customer.domain.CustomerStatusCode;
import ru.petstore.customer.repository.AddressRepository;
import ru.petstore.customer.repository.CustomerRepository;
import ru.petstore.customer.repository.CustomerSpecifications;
import ru.petstore.customer.repository.CustomerStatusRepository;
import ru.petstore.customer.web.dto.CustomerFilterRequest;
import ru.petstore.customer.web.dto.CustomerRequest;
import ru.petstore.customer.web.dto.CustomerResponse;
import ru.petstore.customer.web.dto.DeliveryTargetResponse;

@Service
@Transactional(readOnly = true)
public class CustomerService {

    private static final SortedSet<String> SORTABLE = Collections.unmodifiableSortedSet(new TreeSet<>(
            List.of("email", "firstName", "lastName", "createdAt", "updatedAt")));

    private static final String EMAIL_INDEX = "uq_customer_email";

    private final CustomerRepository customerRepository;
    private final AddressRepository addressRepository;
    private final CustomerStatusRepository customerStatusRepository;
    private final ReferenceDataService referenceDataService;

    public CustomerService(CustomerRepository customerRepository,
                           AddressRepository addressRepository,
                           CustomerStatusRepository customerStatusRepository,
                           ReferenceDataService referenceDataService) {
        this.customerRepository = customerRepository;
        this.addressRepository = addressRepository;
        this.customerStatusRepository = customerStatusRepository;
        this.referenceDataService = referenceDataService;
    }

    public PageResponse<CustomerResponse> search(CustomerFilterRequest filter, Pageable pageable) {
        checkSortable(pageable.getSort());

        Specification<Customer> spec = Specification.allOf(
                CustomerSpecifications.statusIs(
                        referenceDataService.getIdOrNull(ReferenceType.CUSTOMER_STATUS, filter.status())),
                CustomerSpecifications.matches(filter.search()));

        return PageResponse.of(customerRepository.findAll(spec, pageable), CustomerResponse::of);
    }

    public CustomerResponse get(UUID id) {
        return CustomerResponse.of(require(id));
    }

    /**
     * The single call order-service makes: whom the order is for and where it goes.
     * Without {@code addressId} the delivery goes to the address marked as default.
     */
    public DeliveryTargetResponse deliveryTarget(UUID customerId, UUID addressId) {
        Customer customer = require(customerId);
        Address address = addressId == null
                ? addressRepository.findByCustomerIdAndDefaultAddressTrue(customerId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Customer " + customerId + " has no delivery address"))
                : addressRepository.findByIdAndCustomerId(addressId, customerId)
                        .orElseThrow(() -> ResourceNotFoundException.of("Address", addressId));

        return DeliveryTargetResponse.of(customer, address);
    }

    @Transactional
    public CustomerResponse create(CustomerRequest request) {
        String email = normalized(request.email());
        if (customerRepository.existsByEmail(email)) {
            throw duplicateEmail(email);
        }
        return applyAndSave(request, email, new Customer(), CustomerStatusCode.NEW.code());
    }

    @Transactional
    public CustomerResponse update(UUID id, CustomerRequest request) {
        Customer customer = require(id);
        String email = normalized(request.email());

        if (!email.equals(customer.getEmail()) && customerRepository.existsByEmail(email)) {
            throw duplicateEmail(email);
        }

        return applyAndSave(request, email, customer, customer.getStatus().getCode());
    }

    @Transactional
    public void delete(UUID id) {
        customerRepository.delete(require(id));
    }

    private CustomerResponse applyAndSave(CustomerRequest request, String email,
                                          Customer customer, String statusWhenAbsent) {
        ReferenceItem status = referenceDataService.getRequired(
                ReferenceType.CUSTOMER_STATUS, request.statusCodeOr(statusWhenAbsent));

        customer.setEmail(email);
        customer.setPhone(request.phone());
        customer.setFirstName(request.firstName());
        customer.setLastName(request.lastName());
        customer.setStatus(customerStatusRepository.getReferenceById(status.id()));

        try {
            customerRepository.saveAndFlush(customer);
        } catch (DataIntegrityViolationException e) {
            throw translate(e, email);
        }

        return CustomerResponse.of(customer, ReferenceResponse.of(status));
    }

    private static String normalized(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private Customer require(UUID id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Customer", id));
    }

    private static void checkSortable(Sort sort) {
        sort.forEach(order -> {
            if (!SORTABLE.contains(order.getProperty())) {
                throw new IllegalArgumentException("Cannot sort by " + order.getProperty()
                        + "; sortable properties: " + String.join(", ", SORTABLE));
            }
        });
    }

    private static RuntimeException translate(DataIntegrityViolationException e, String email) {
        String cause = String.valueOf(e.getMostSpecificCause().getMessage());
        return cause.contains(EMAIL_INDEX) ? duplicateEmail(email, e) : e;
    }

    private static IllegalArgumentException duplicateEmail(String email) {
        return duplicateEmail(email, null);
    }

    private static IllegalArgumentException duplicateEmail(String email, Throwable cause) {
        return new IllegalArgumentException("Customer with email " + email + " already exists", cause);
    }
}
