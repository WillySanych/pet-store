package ru.petstore.order.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import ru.petstore.order.domain.OrderItem;

@Schema(description = "Позиция заказа")
public record OrderItemResponse(
        UUID productId,
        String productName,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal amount) {

    public static OrderItemResponse of(OrderItem item) {
        return new OrderItemResponse(item.getProductId(), item.getProductName(),
                item.getUnitPrice(), item.getQuantity(), item.amount());
    }
}
