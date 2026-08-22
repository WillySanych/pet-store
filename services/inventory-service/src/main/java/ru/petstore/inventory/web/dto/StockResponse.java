package ru.petstore.inventory.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import ru.petstore.common.web.ReferenceResponse;
import ru.petstore.inventory.domain.StockItem;

@Schema(description = "Остаток по товару")
public record StockResponse(
        @Schema(description = "Идентификатор товара в каталоге") UUID productId,
        @Schema(description = "Склад") ReferenceResponse warehouse,
        @Schema(description = "На складе, включая зарезервированное", example = "40") int quantity,
        @Schema(description = "Удерживается открытыми резервами", example = "5") int reserved,
        @Schema(description = "Доступно к заказу: quantity - reserved", example = "35") int available) {

    public static StockResponse of(StockItem item) {
        return new StockResponse(item.getProductId(), ReferenceResponse.of(item.getWarehouse()),
                item.getQuantity(), item.getReserved(), item.available());
    }

    public static StockResponse of(StockItem item, ReferenceResponse warehouse) {
        return new StockResponse(item.getProductId(), warehouse,
                item.getQuantity(), item.getReserved(), item.available());
    }
}
