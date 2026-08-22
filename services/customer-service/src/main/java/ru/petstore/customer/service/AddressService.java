package ru.petstore.customer.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.petstore.common.reference.ReferenceDataService;
import ru.petstore.common.reference.ReferenceItem;
import ru.petstore.common.web.ConcurrentChangeException;
import ru.petstore.common.web.ReferenceResponse;
import ru.petstore.common.web.ResourceNotFoundException;
import ru.petstore.customer.domain.Address;
import ru.petstore.customer.domain.Customer;
import ru.petstore.customer.repository.AddressRepository;
import ru.petstore.customer.repository.CityRepository;
import ru.petstore.customer.repository.CustomerRepository;
import ru.petstore.customer.web.dto.AddressRequest;
import ru.petstore.customer.web.dto.AddressResponse;

/**
 * Addresses of a customer, and the invariant order-service leans on: a customer who has addresses
 * has exactly one default among them.
 */
@Service
@Transactional(readOnly = true)
public class AddressService {

    private static final String DEFAULT_INDEX = "uq_address_default";

    private final AddressRepository addressRepository;
    private final CustomerRepository customerRepository;
    private final CityRepository cityRepository;
    private final ReferenceDataService referenceDataService;

    public AddressService(AddressRepository addressRepository,
                          CustomerRepository customerRepository,
                          CityRepository cityRepository,
                          ReferenceDataService referenceDataService) {
        this.addressRepository = addressRepository;
        this.customerRepository = customerRepository;
        this.cityRepository = cityRepository;
        this.referenceDataService = referenceDataService;
    }

    public List<AddressResponse> list(UUID customerId) {
        if (!customerRepository.existsById(customerId)) {
            throw ResourceNotFoundException.of("Customer", customerId);
        }
        return addressRepository.findAllByCustomerIdOrderByCreatedAtAsc(customerId).stream()
                .map(AddressResponse::of)
                .toList();
    }

    public AddressResponse get(UUID customerId, UUID addressId) {
        return AddressResponse.of(require(customerId, addressId));
    }

    @Transactional
    public AddressResponse create(UUID customerId, AddressRequest request) {
        Customer customer = requireCustomer(customerId);
        boolean first = !addressRepository.existsByCustomerId(customerId);
        boolean makeDefault = first || request.defaultAddressOr(false);

        if (makeDefault && !first) {
            addressRepository.clearDefault(customerId, Instant.now());
        }

        Address address = new Address();
        address.setCustomer(customer);
        address.setDefaultAddress(makeDefault);
        return applyAndSave(request, address, customerId);
    }

    @Transactional
    public AddressResponse update(UUID customerId, UUID addressId, AddressRequest request) {
        Address address = require(customerId, addressId);
        boolean makeDefault = request.defaultAddressOr(address.isDefaultAddress());

        if (address.isDefaultAddress() && !makeDefault) {
            throw new IllegalArgumentException("Cannot clear the default flag of address " + addressId
                    + ": mark another address of customer " + customerId + " as default instead");
        }
        if (makeDefault && !address.isDefaultAddress()) {
            addressRepository.clearDefault(customerId, Instant.now());
            address.setDefaultAddress(true);
        }

        return applyAndSave(request, address, customerId);
    }

    @Transactional
    public void delete(UUID customerId, UUID addressId) {
        Address address = require(customerId, addressId);

        addressRepository.delete(address);
        addressRepository.flush();

        if (address.isDefaultAddress()) {
            addressRepository.findFirstByCustomerIdOrderByCreatedAtAsc(customerId)
                    .ifPresent(next -> {
                        next.setDefaultAddress(true);
                        try {
                            addressRepository.saveAndFlush(next);
                        } catch (DataIntegrityViolationException e) {
                            throw translate(e, customerId);
                        }
                    });
        }
    }

    private AddressResponse applyAndSave(AddressRequest request, Address address, UUID customerId) {
        ReferenceItem city = referenceDataService.getRequired(ReferenceType.CITY, request.cityCode());

        address.setCity(cityRepository.getReferenceById(city.id()));
        address.setStreet(request.street());
        address.setBuilding(request.building());
        address.setApartment(request.apartment());
        address.setPostalCode(request.postalCode());

        try {
            addressRepository.saveAndFlush(address);
        } catch (DataIntegrityViolationException e) {
            throw translate(e, customerId);
        }

        return AddressResponse.of(address, ReferenceResponse.of(city));
    }

    private Customer requireCustomer(UUID customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> ResourceNotFoundException.of("Customer", customerId));
    }

    private Address require(UUID customerId, UUID addressId) {
        return addressRepository.findByIdAndCustomerId(addressId, customerId)
                .orElseThrow(() -> ResourceNotFoundException.of("Address", addressId));
    }

    private static RuntimeException translate(DataIntegrityViolationException e, UUID customerId) {
        String cause = String.valueOf(e.getMostSpecificCause().getMessage());
        return cause.contains(DEFAULT_INDEX)
                ? new ConcurrentChangeException(
                        "Default address of customer " + customerId + " changed concurrently", e)
                : e;
    }
}
