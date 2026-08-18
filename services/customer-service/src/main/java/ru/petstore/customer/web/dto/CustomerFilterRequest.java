package ru.petstore.customer.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record CustomerFilterRequest(
        @Schema(description = "Код статуса клиента", example = "ACTIVE") String status,
        @Schema(description = "Часть почты, имени или фамилии", example = "иван") String search) {
}
