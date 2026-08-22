package ru.petstore.order.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.petstore.common.web.ReferenceResponse;
import ru.petstore.order.domain.DeliveryAddress;

@Schema(description = "Адрес доставки на момент оформления заказа")
public record DeliveryAddressResponse(
        ReferenceResponse city,
        String street,
        String building,
        String apartment,
        String postalCode) {

    public static DeliveryAddressResponse of(DeliveryAddress address) {
        return new DeliveryAddressResponse(
                new ReferenceResponse(address.getCityCode(), address.getCityName()),
                address.getStreet(),
                address.getBuilding(),
                address.getApartment(),
                address.getPostalCode());
    }
}
