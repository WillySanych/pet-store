package ru.petstore.catalog.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import ru.petstore.catalog.domain.Product;
import ru.petstore.common.web.ReferenceResponse;

@Schema(description = "Товар каталога")
public record ProductResponse(
        UUID id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        boolean active,
        ReferenceResponse category,
        ReferenceResponse species,
        ReferenceResponse brand,
        Instant createdAt,
        Instant updatedAt) {

    public static ProductResponse of(Product product) {
        return of(product,
                ReferenceResponse.of(product.getCategory()),
                ReferenceResponse.of(product.getSpecies()),
                ReferenceResponse.of(product.getBrand()));
    }

    public static ProductResponse of(Product product, ReferenceResponse category,
                                     ReferenceResponse species, ReferenceResponse brand) {
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.isActive(),
                category,
                species,
                brand,
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
