package ru.petstore.customer.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Создание или замена адреса")
public record AddressRequest(
        @NotBlank @Schema(example = "MSK") String cityCode,

        @NotBlank @Size(max = 255) @Schema(example = "Дмитровское шоссе") String street,

        @NotBlank @Size(max = 20) @Schema(example = "9к3") String building,

        @Size(max = 20) @Schema(example = "154") String apartment,

        @Pattern(regexp = "[0-9]{6}", message = "должен быть шестизначным индексом")
        @Schema(example = "127434") String postalCode,

        @Schema(description = "Адрес доставки по умолчанию. Первый адрес клиента становится "
                + "основным независимо от поля", example = "true") Boolean defaultAddress) {

    public boolean defaultAddressOr(boolean current) {
        return defaultAddress == null ? current : defaultAddress;
    }
}
