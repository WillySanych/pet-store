package ru.petstore.catalog.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@Schema(description = "Создание или замена товара")
public record ProductRequest(
        @NotBlank
        @Schema(example = "RC-DOG-ADULT-3KG") String sku,

        @NotBlank
        @Schema(example = "Royal Canin Medium Adult 3 кг") String name,

        String description,

        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2)
        @Schema(example = "2499.00") BigDecimal price,

        @NotBlank @Schema(example = "FOOD") String categoryCode,
        @NotBlank @Schema(example = "DOG") String speciesCode,
        @NotBlank @Schema(example = "ROYAL_CANIN") String brandCode,

        @Schema(description = "Отсутствие поля: при создании товар активен, "
                + "при обновлении признак сохраняется", example = "true") Boolean active) {
    public boolean activeOr(boolean current) {
        return active == null ? current : active;
    }
}
