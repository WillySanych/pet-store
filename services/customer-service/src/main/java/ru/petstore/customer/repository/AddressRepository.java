package ru.petstore.customer.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.petstore.customer.domain.Address;

public interface AddressRepository extends JpaRepository<Address, UUID> {

    @EntityGraph(attributePaths = "city")
    List<Address> findAllByCustomerIdOrderByCreatedAtAsc(UUID customerId);

    @EntityGraph(attributePaths = "city")
    Optional<Address> findByIdAndCustomerId(UUID id, UUID customerId);

    @EntityGraph(attributePaths = "city")
    Optional<Address> findByCustomerIdAndDefaultAddressTrue(UUID customerId);

    Optional<Address> findFirstByCustomerIdOrderByCreatedAtAsc(UUID customerId);

    boolean existsByCustomerId(UUID customerId);

    /**
     * Clears the current default before another address claims it.
     */
    @Modifying(flushAutomatically = true)
    @Query("update Address a set a.defaultAddress = false, a.updatedAt = :now "
            + "where a.customer.id = :customerId and a.defaultAddress = true")
    int clearDefault(@Param("customerId") UUID customerId, @Param("now") Instant now);
}
