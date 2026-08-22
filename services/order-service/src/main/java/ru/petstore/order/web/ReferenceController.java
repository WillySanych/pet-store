package ru.petstore.order.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.petstore.common.reference.ReferenceDataService;
import ru.petstore.common.web.ReferenceResponse;
import ru.petstore.order.service.ReferenceType;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Справочники")
public class ReferenceController {

    private final ReferenceDataService referenceDataService;

    public ReferenceController(ReferenceDataService referenceDataService) {
        this.referenceDataService = referenceDataService;
    }

    @GetMapping("/order-statuses")
    @Operation(summary = "Статусы заказов")
    public List<ReferenceResponse> orderStatuses() {
        return view(ReferenceType.ORDER_STATUS);
    }

    @GetMapping("/delivery-types")
    @Operation(summary = "Способы доставки")
    public List<ReferenceResponse> deliveryTypes() {
        return view(ReferenceType.DELIVERY_TYPE);
    }

    @GetMapping("/payment-methods")
    @Operation(summary = "Способы оплаты")
    public List<ReferenceResponse> paymentMethods() {
        return view(ReferenceType.PAYMENT_METHOD);
    }

    private List<ReferenceResponse> view(ReferenceType type) {
        return referenceDataService.getAll(type).stream().map(ReferenceResponse::of).toList();
    }
}
