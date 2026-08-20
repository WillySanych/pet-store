package ru.petstore.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.petstore.order.domain.PaymentMethod;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, Long> {
}
