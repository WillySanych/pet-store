package ru.petstore.common.web;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.petstore.common.reference.ReferenceEntity;
import ru.petstore.common.reference.ReferenceItem;

/** Reference item as every service returns it: from the cache or straight from an entity. */
@Schema(description = "Элемент справочника")
public record ReferenceResponse(
        @Schema(description = "Код") String code,
        @Schema(description = "Название") String name) {

    public static ReferenceResponse of(ReferenceItem item) {
        return new ReferenceResponse(item.code(), item.name());
    }

    public static ReferenceResponse of(ReferenceEntity entity) {
        return new ReferenceResponse(entity.getCode(), entity.getName());
    }
}
