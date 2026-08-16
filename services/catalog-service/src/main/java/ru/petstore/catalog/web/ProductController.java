package ru.petstore.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.petstore.catalog.service.ProductService;
import ru.petstore.common.overload.OverloadProtection;
import ru.petstore.catalog.web.dto.PageResponse;
import ru.petstore.catalog.web.dto.ProductFilterRequest;
import ru.petstore.catalog.web.dto.ProductRequest;
import ru.petstore.catalog.web.dto.ProductResponse;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Товары")
public class ProductController {

    /** The metric label: the path template, never the actual URI. */
    private static final String LIST_ENDPOINT = "GET /api/v1/products";

    private final ProductService productService;
    private final OverloadProtection overloadProtection;

    public ProductController(ProductService productService, OverloadProtection overloadProtection) {
        this.productService = productService;
        this.overloadProtection = overloadProtection;
    }

    @GetMapping
    @Operation(summary = "Список товаров с фильтрами и пагинацией")
    public PageResponse<ProductResponse> list(
            @ParameterObject ProductFilterRequest filter,
            @ParameterObject @PageableDefault(sort = "name") Pageable pageable) {
        return overloadProtection.call(LIST_ENDPOINT, () -> productService.search(filter, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Товар по идентификатору")
    public ProductResponse get(@PathVariable UUID id) {
        return productService.get(id);
    }

    @PostMapping
    @Operation(summary = "Создать товар")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse created = productService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/products/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Заменить товар целиком")
    public ProductResponse update(@PathVariable UUID id, @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }
}
