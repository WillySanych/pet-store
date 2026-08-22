package ru.petstore.inventory.service;

import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.petstore.common.reference.ReferenceDataService;
import ru.petstore.common.reference.ReferenceItem;
import ru.petstore.common.web.ConcurrentChangeException;
import ru.petstore.common.web.ReferenceResponse;
import ru.petstore.common.web.ResourceNotFoundException;
import ru.petstore.inventory.domain.StockItem;
import ru.petstore.inventory.repository.StockItemRepository;
import ru.petstore.inventory.repository.WarehouseRepository;
import ru.petstore.inventory.web.dto.StockRequest;
import ru.petstore.inventory.web.dto.StockResponse;

@Service
@Transactional(readOnly = true)
public class StockService {

    private static final String PRODUCT_INDEX = "uq_stock_item_product";

    private final StockItemRepository stockItemRepository;
    private final WarehouseRepository warehouseRepository;
    private final ReferenceDataService referenceDataService;

    public StockService(StockItemRepository stockItemRepository,
                        WarehouseRepository warehouseRepository,
                        ReferenceDataService referenceDataService) {
        this.stockItemRepository = stockItemRepository;
        this.warehouseRepository = warehouseRepository;
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

        StockItem item = stockItemRepository.findByProductId(productId).orElseGet(() -> {
            StockItem created = new StockItem();
            created.setProductId(productId);
            return created;
        });

        if (request.quantity() < item.getReserved()) {
            throw new IllegalArgumentException("Cannot set quantity of product " + productId
                    + " to " + request.quantity() + ": " + item.getReserved() + " is held by reservations");
        }

        item.setWarehouse(warehouseRepository.getReferenceById(warehouse.id()));
        item.setQuantity(request.quantity());

        try {
            stockItemRepository.saveAndFlush(item);
        } catch (OptimisticLockingFailureException e) {
            throw new ConcurrentChangeException(
                    "Stock for product " + productId + " changed concurrently", e);
        } catch (DataIntegrityViolationException e) {
            throw translate(e, productId);
        }

        return StockResponse.of(item, ReferenceResponse.of(warehouse));
    }

    private static RuntimeException translate(DataIntegrityViolationException e, UUID productId) {
        String cause = String.valueOf(e.getMostSpecificCause().getMessage());
        return cause.contains(PRODUCT_INDEX)
                ? new ConcurrentChangeException("Stock for product " + productId + " is being created "
                        + "by another request", e)
                : e;
    }
}
