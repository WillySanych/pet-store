package ru.petstore.catalog.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.petstore.catalog.domain.ReferenceEntity;
import ru.petstore.catalog.service.ReferenceItem;

@Schema(description = "Элемент справочника")
public record ReferenceResponse(
        @Schema(description = "Код", example = "FOOD") String code,
        @Schema(description = "Название", example = "Корма") String name) {

    public static ReferenceResponse of(ReferenceItem item) {
        return new ReferenceResponse(item.code(), item.name());
    }

    public static ReferenceResponse of(ReferenceEntity entity) {
        return new ReferenceResponse(entity.getCode(), entity.getName());
    }
}
