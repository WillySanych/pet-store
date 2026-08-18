package ru.petstore.customer.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.petstore.customer.domain.Address;
import ru.petstore.customer.domain.Customer;

/**
 * What order-service asks for in one call: whom the order is for and where it goes.
 */
@Schema(description = "Клиент и адрес доставки для оформления заказа")
public record DeliveryTargetResponse(CustomerResponse customer, AddressResponse address) {

    public static DeliveryTargetResponse of(Customer customer, Address address) {
        return new DeliveryTargetResponse(CustomerResponse.of(customer), AddressResponse.of(address));
    }
}
