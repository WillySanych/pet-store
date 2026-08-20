package ru.petstore.order.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ru.petstore.order.domain.CustomerOrder;

@Schema(description = "Заказ")
public record OrderResponse(
        UUID id,
        UUID customerId,
        String customerEmail,
        ReferenceResponse status,
        ReferenceResponse deliveryType,
        ReferenceResponse paymentMethod,
        DeliveryAddressResponse address,
        List<OrderItemResponse> items,
        BigDecimal totalAmount,
        Instant createdAt,
        Instant updatedAt) {

    public static OrderResponse of(CustomerOrder order) {
        return of(order,
                ReferenceResponse.of(order.getStatus()),
                ReferenceResponse.of(order.getDeliveryType()),
                ReferenceResponse.of(order.getPaymentMethod()));
    }

    public static OrderResponse of(CustomerOrder order, ReferenceResponse status,
                                   ReferenceResponse deliveryType, ReferenceResponse paymentMethod) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getCustomerEmail(),
                status,
                deliveryType,
                paymentMethod,
                DeliveryAddressResponse.of(order.getAddress()),
                order.getItems().stream().map(OrderItemResponse::of).toList(),
                order.getTotalAmount(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
