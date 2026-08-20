package ru.petstore.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.petstore.order.domain.DeliveryType;

public interface DeliveryTypeRepository extends JpaRepository<DeliveryType, Long> {
}
