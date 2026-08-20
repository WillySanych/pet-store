package ru.petstore.order.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.petstore.order.domain.CustomerOrder;

public interface OrderRepository extends JpaRepository<CustomerOrder, UUID> {

    @EntityGraph(attributePaths = {"status", "deliveryType", "paymentMethod", "items"})
    Optional<CustomerOrder> findWithItemsById(UUID id);

    @EntityGraph(attributePaths = {"status", "deliveryType", "paymentMethod", "items"})
    Optional<CustomerOrder> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    @EntityGraph(attributePaths = {"status", "deliveryType", "paymentMethod"})
    Page<CustomerOrder> findByCustomerId(UUID customerId, Pageable pageable);
}
