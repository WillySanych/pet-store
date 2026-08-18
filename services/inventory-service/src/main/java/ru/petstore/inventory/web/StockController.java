package ru.petstore.inventory.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.petstore.inventory.service.StockService;
import ru.petstore.inventory.web.dto.StockRequest;
import ru.petstore.inventory.web.dto.StockResponse;

@RestController
@RequestMapping("/api/v1/stock")
@Tag(name = "Остатки")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @GetMapping("/{productId}")
    @Operation(summary = "Остаток по товару")
    public StockResponse get(@PathVariable UUID productId) {
        return stockService.get(productId);
    }

    @PutMapping("/{productId}")
    @Operation(summary = "Установить остаток по товару")
    public StockResponse set(@PathVariable UUID productId, @Valid @RequestBody StockRequest request) {
        return stockService.set(productId, request);
    }
}
