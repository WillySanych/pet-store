package ru.petstore.customer.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import ru.petstore.customer.domain.Address;

@Schema(description = "Адрес клиента")
public record AddressResponse(
        UUID id,
        ReferenceResponse city,
        String street,
        String building,
        String apartment,
        String postalCode,
        @Schema(description = "Адрес доставки по умолчанию") boolean defaultAddress,
        Instant createdAt,
        Instant updatedAt) {

    public static AddressResponse of(Address address) {
        return of(address, ReferenceResponse.of(address.getCity()));
    }

    public static AddressResponse of(Address address, ReferenceResponse city) {
        return new AddressResponse(
                address.getId(),
                city,
                address.getStreet(),
                address.getBuilding(),
                address.getApartment(),
                address.getPostalCode(),
                address.isDefaultAddress(),
                address.getCreatedAt(),
                address.getUpdatedAt());
    }
}
