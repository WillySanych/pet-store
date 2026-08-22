package ru.petstore.inventory.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.petstore.common.reference.ReferenceDataService;
import ru.petstore.common.web.ReferenceResponse;
import ru.petstore.inventory.service.ReferenceType;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Справочники")
public class ReferenceController {

    private final ReferenceDataService referenceDataService;

    public ReferenceController(ReferenceDataService referenceDataService) {
        this.referenceDataService = referenceDataService;
    }

    @GetMapping("/warehouses")
    @Operation(summary = "Склады")
    public List<ReferenceResponse> warehouses() {
        return view(ReferenceType.WAREHOUSE);
    }

    @GetMapping("/reservation-statuses")
    @Operation(summary = "Статусы резервов")
    public List<ReferenceResponse> reservationStatuses() {
        return view(ReferenceType.RESERVATION_STATUS);
    }

    private List<ReferenceResponse> view(ReferenceType type) {
        return referenceDataService.getAll(type).stream().map(ReferenceResponse::of).toList();
    }
}
