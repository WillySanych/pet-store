package ru.petstore.customer.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.petstore.common.reference.ReferenceDataService;
import ru.petstore.customer.service.ReferenceType;
import ru.petstore.customer.web.dto.ReferenceResponse;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Справочники")
public class ReferenceController {

    private final ReferenceDataService referenceDataService;

    public ReferenceController(ReferenceDataService referenceDataService) {
        this.referenceDataService = referenceDataService;
    }

    @GetMapping("/cities")
    @Operation(summary = "Города доставки")
    public List<ReferenceResponse> cities() {
        return view(ReferenceType.CITY);
    }

    @GetMapping("/customer-statuses")
    @Operation(summary = "Статусы клиентов")
    public List<ReferenceResponse> customerStatuses() {
        return view(ReferenceType.CUSTOMER_STATUS);
    }

    private List<ReferenceResponse> view(ReferenceType type) {
        return referenceDataService.getAll(type).stream().map(ReferenceResponse::of).toList();
    }
}
