package ru.petstore.order.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.petstore.order.domain.OrderStatusHistory;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, UUID> {

    @EntityGraph(attributePaths = "status")
    List<OrderStatusHistory> findByOrderIdOrderByChangedAtAsc(UUID orderId);
}
