package ru.petstore.customer.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.petstore.customer.service.AddressService;
import ru.petstore.customer.web.dto.AddressRequest;
import ru.petstore.customer.web.dto.AddressResponse;

@RestController
@RequestMapping("/api/v1/customers/{customerId}/addresses")
@Tag(name = "Адреса")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping
    @Operation(summary = "Адреса клиента")
    public List<AddressResponse> list(@PathVariable UUID customerId) {
        return addressService.list(customerId);
    }

    @GetMapping("/{addressId}")
    @Operation(summary = "Адрес клиента по идентификатору")
    public AddressResponse get(@PathVariable UUID customerId, @PathVariable UUID addressId) {
        return addressService.get(customerId, addressId);
    }

    @PostMapping
    @Operation(summary = "Добавить адрес")
    public ResponseEntity<AddressResponse> create(@PathVariable UUID customerId,
                                                  @Valid @RequestBody AddressRequest request) {
        AddressResponse created = addressService.create(customerId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/customers/" + customerId + "/addresses/" + created.id()))
                .body(created);
    }

    @PutMapping("/{addressId}")
    @Operation(summary = "Заменить адрес целиком")
    public AddressResponse update(@PathVariable UUID customerId, @PathVariable UUID addressId,
                                  @Valid @RequestBody AddressRequest request) {
        return addressService.update(customerId, addressId, request);
    }

    @DeleteMapping("/{addressId}")
    @Operation(summary = "Удалить адрес")
    public ResponseEntity<Void> delete(@PathVariable UUID customerId, @PathVariable UUID addressId) {
        addressService.delete(customerId, addressId);
        return ResponseEntity.noContent().build();
    }
}
