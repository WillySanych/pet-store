package ru.petstore.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.petstore.order.domain.OrderStatus;

public interface OrderStatusRepository extends JpaRepository<OrderStatus, Long> {
}
