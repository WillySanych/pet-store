package ru.petstore.customer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.petstore.customer.domain.CustomerStatus;

public interface CustomerStatusRepository extends JpaRepository<CustomerStatus, Long> {
}
