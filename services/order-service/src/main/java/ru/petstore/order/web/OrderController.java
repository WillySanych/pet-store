package ru.petstore.order.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.petstore.order.service.OrderCreation;
import ru.petstore.order.service.OrderService;
import ru.petstore.order.web.dto.OrderRequest;
import ru.petstore.order.web.dto.OrderResponse;
import ru.petstore.order.web.dto.OrderStatusHistoryResponse;
import ru.petstore.order.web.dto.PageResponse;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Заказы")
public class OrderController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @Operation(summary = "Оформить заказ",
            description = "С заголовком Idempotency-Key повтор возвращает уже созданный заказ и 200 "
                    + "вместо 201, а не оформляет второй.")
    public ResponseEntity<OrderResponse> create(
            @Parameter(description = "Ключ идемпотентности вызывающего")
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody OrderRequest request) {
        OrderCreation creation = orderService.create(request, idempotencyKey);
        return creation.created()
                ? ResponseEntity.created(URI.create("/api/v1/orders/" + creation.order().id()))
                        .body(creation.order())
                : ResponseEntity.ok(creation.order());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Заказ по идентификатору")
    public OrderResponse get(@PathVariable UUID id) {
        return orderService.get(id);
    }

    @GetMapping
    @Operation(summary = "Заказы клиента с пагинацией")
    public PageResponse<OrderResponse> list(
            @RequestParam UUID customerId,
            @ParameterObject @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return orderService.listByCustomer(customerId, pageable);
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "История статусов заказа")
    public List<OrderStatusHistoryResponse> history(@PathVariable UUID id) {
        return orderService.history(id);
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Подтвердить заказ",
            description = "Имитация оплаты: статус CONFIRMED и событие ORDER_CONFIRMED в Kafka. "
                    + "Идемпотентен.")
    public OrderResponse confirm(@PathVariable UUID id) {
        return orderService.confirm(id);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Отменить заказ",
            description = "Статус CANCELLED и событие ORDER_CANCELLED в Kafka. Идемпотентен; "
                    + "подтверждённый заказ отменить нельзя — остаток уже списан.")
    public OrderResponse cancel(@PathVariable UUID id) {
        return orderService.cancel(id);
    }
}
