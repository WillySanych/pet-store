package ru.petstore.customer.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import ru.petstore.customer.domain.Customer;

public interface CustomerRepository extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {

    @EntityGraph(attributePaths = "status")
    Page<Customer> findAll(Specification<Customer> spec, Pageable pageable);

    @EntityGraph(attributePaths = "status")
    Optional<Customer> findById(UUID id);

    boolean existsByEmail(String email);
}
