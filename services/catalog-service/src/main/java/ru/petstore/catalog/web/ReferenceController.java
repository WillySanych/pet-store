package ru.petstore.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.petstore.catalog.service.ReferenceType;
import ru.petstore.catalog.web.dto.ReferenceResponse;
import ru.petstore.common.reference.ReferenceDataService;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Справочники")
public class ReferenceController {

    private final ReferenceDataService referenceDataService;

    public ReferenceController(ReferenceDataService referenceDataService) {
        this.referenceDataService = referenceDataService;
    }

    @GetMapping("/categories")
    @Operation(summary = "Категории товаров")
    public List<ReferenceResponse> categories() {
        return view(ReferenceType.CATEGORY);
    }

    @GetMapping("/species")
    @Operation(summary = "Виды животных")
    public List<ReferenceResponse> species() {
        return view(ReferenceType.SPECIES);
    }

    @GetMapping("/brands")
    @Operation(summary = "Бренды")
    public List<ReferenceResponse> brands() {
        return view(ReferenceType.BRAND);
    }

    private List<ReferenceResponse> view(ReferenceType type) {
        return referenceDataService.getAll(type).stream().map(ReferenceResponse::of).toList();
    }
}
