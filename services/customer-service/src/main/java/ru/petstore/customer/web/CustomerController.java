package ru.petstore.customer.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.petstore.common.web.PageResponse;
import ru.petstore.customer.service.CustomerService;
import ru.petstore.customer.web.dto.CustomerFilterRequest;
import ru.petstore.customer.web.dto.CustomerRequest;
import ru.petstore.customer.web.dto.CustomerResponse;
import ru.petstore.customer.web.dto.DeliveryTargetResponse;

@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Клиенты")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    @Operation(summary = "Список клиентов с фильтрами и пагинацией")
    public PageResponse<CustomerResponse> list(
            @ParameterObject CustomerFilterRequest filter,
            @ParameterObject @PageableDefault(sort = "lastName") Pageable pageable) {
        return customerService.search(filter, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Клиент по идентификатору")
    public CustomerResponse get(@PathVariable UUID id) {
        return customerService.get(id);
    }

    @GetMapping("/{id}/delivery-target")
    @Operation(summary = "Клиент и адрес доставки одним ответом",
            description = "Эндпоинт для order-service. Без addressId отдаётся адрес по умолчанию.")
    public DeliveryTargetResponse deliveryTarget(
            @PathVariable UUID id,
            @Parameter(description = "Конкретный адрес клиента вместо основного")
            @RequestParam(required = false) UUID addressId) {
        return customerService.deliveryTarget(id, addressId);
    }

    @PostMapping
    @Operation(summary = "Завести клиента")
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest request) {
        CustomerResponse created = customerService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/customers/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Заменить клиента целиком")
    public CustomerResponse update(@PathVariable UUID id, @Valid @RequestBody CustomerRequest request) {
        return customerService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить клиента вместе с его адресами")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        customerService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
