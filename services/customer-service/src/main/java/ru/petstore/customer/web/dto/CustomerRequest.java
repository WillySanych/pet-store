package ru.petstore.customer.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Создание или замена клиента")
public record CustomerRequest(
        @NotBlank @Email @Size(max = 255)
        @Schema(example = "ivan.petrov@example.com") String email,

        @Pattern(regexp = "\\+?[0-9]{10,15}", message = "должен быть номером из 10-15 цифр")
        @Schema(example = "+79161234567") String phone,

        @NotBlank @Size(max = 100) @Schema(example = "Иван") String firstName,

        @NotBlank @Size(max = 100) @Schema(example = "Петров") String lastName,

        @Schema(description = "Отсутствие поля: при создании клиент заводится как NEW, "
                + "при обновлении статус сохраняется", example = "ACTIVE") String statusCode) {

    public String statusCodeOr(String current) {
        return statusCode == null || statusCode.isBlank() ? current : statusCode;
    }
}
