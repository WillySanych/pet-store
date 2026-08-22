package ru.petstore.order.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import ru.petstore.common.web.ReferenceResponse;
import ru.petstore.order.domain.OrderStatusHistory;

@Schema(description = "Переход статуса заказа")
public record OrderStatusHistoryResponse(ReferenceResponse status, Instant changedAt) {

    public static OrderStatusHistoryResponse of(OrderStatusHistory row) {
        return new OrderStatusHistoryResponse(ReferenceResponse.of(row.getStatus()), row.getChangedAt());
    }
}
