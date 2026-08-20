package ru.petstore.order.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(description = "Позиция заказа")
public record OrderItemRequest(
        @NotNull @Schema(description = "Товар каталога") UUID productId,
        @Min(1) @Max(1000) @Schema(example = "2") int quantity) {
}
