package ru.petstore.customer.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import ru.petstore.common.web.ReferenceResponse;
import ru.petstore.customer.domain.Customer;

@Schema(description = "Клиент")
public record CustomerResponse(
        UUID id,
        String email,
        String phone,
        String firstName,
        String lastName,
        ReferenceResponse status,
        Instant createdAt,
        Instant updatedAt) {

    public static CustomerResponse of(Customer customer) {
        return of(customer, ReferenceResponse.of(customer.getStatus()));
    }

    public static CustomerResponse of(Customer customer, ReferenceResponse status) {
        return new CustomerResponse(
                customer.getId(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getFirstName(),
                customer.getLastName(),
                status,
                customer.getCreatedAt(),
                customer.getUpdatedAt());
    }
}
