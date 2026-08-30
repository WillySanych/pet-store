package ru.petstore.inventory.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.petstore.common.reference.ReferenceDataService;
import ru.petstore.common.reference.ReferenceItem;
import ru.petstore.common.web.ReferenceResponse;
import ru.petstore.common.web.ResourceNotFoundException;
import ru.petstore.inventory.domain.StockItem;
import ru.petstore.inventory.repository.StockItemRepository;
import ru.petstore.inventory.web.dto.StockRequest;
import ru.petstore.inventory.web.dto.StockResponse;

@Service
@Transactional(readOnly = true)
public class StockService {

    private final StockItemRepository stockItemRepository;
    private final ReferenceDataService referenceDataService;

    public StockService(StockItemRepository stockItemRepository,
                        ReferenceDataService referenceDataService) {
        this.stockItemRepository = stockItemRepository;
        this.referenceDataService = referenceDataService;
    }

    public StockResponse get(UUID productId) {
        return stockItemRepository.findByProductId(productId)
                .map(StockResponse::of)
                .orElseThrow(() -> ResourceNotFoundException.of("Stock item", productId));
    }

    @Transactional
    public StockResponse set(UUID productId, StockRequest request) {
        ReferenceItem warehouse =
                referenceDataService.getRequired(ReferenceType.WAREHOUSE, request.warehouseCode());

        int changed = stockItemRepository.upsertQuantity(
                UUID.randomUUID(), productId, warehouse.id(), request.quantity());
        if (changed == 0) {
            int reserved = stockItemRepository.findByProductId(productId)
                    .map(StockItem::getReserved)
                    .orElse(0);
            throw new IllegalArgumentException("Cannot set quantity of product " + productId
                    + " to " + request.quantity() + ": " + reserved + " is held by reservations");
        }

        StockItem item = stockItemRepository.findByProductId(productId)
                .orElseThrow(() -> ResourceNotFoundException.of("Stock item", productId));
        return StockResponse.of(item, ReferenceResponse.of(warehouse));
    }
}
