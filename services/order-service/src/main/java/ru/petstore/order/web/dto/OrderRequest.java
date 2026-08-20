package ru.petstore.order.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@Schema(description = "Оформление заказа")
public record OrderRequest(
        @NotNull UUID customerId,

        @Schema(description = "Адрес доставки; без него берётся основной адрес клиента")
        UUID addressId,

        @NotBlank @Schema(example = "COURIER") String deliveryTypeCode,

        @NotBlank @Schema(example = "CARD") String paymentMethodCode,

        @NotEmpty @Size(max = 100) @Valid List<OrderItemRequest> items) {
}
