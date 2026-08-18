package ru.petstore.inventory.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Установка остатка по товару")
public record StockRequest(
        @NotBlank
        @Schema(description = "Код склада", example = "MSK", requiredMode = Schema.RequiredMode.REQUIRED)
        String warehouseCode,

        @NotNull
        @Min(0)
        @Schema(description = "Сколько есть на складе", example = "40",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Integer quantity) {
}
