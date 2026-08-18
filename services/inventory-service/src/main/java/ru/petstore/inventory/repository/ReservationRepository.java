package ru.petstore.inventory.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.petstore.inventory.domain.Reservation;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    @EntityGraph(attributePaths = {"items", "status"})
    Optional<Reservation> findByOrderId(UUID orderId);

    @EntityGraph(attributePaths = {"items", "status"})
    Optional<Reservation> findWithItemsById(UUID id);

    @Query("""
            select r.id from Reservation r
            where r.status.code = :statusCode and r.expiresAt < :deadline
            order by r.expiresAt asc""")
    List<UUID> findOverdueIds(String statusCode, Instant deadline, Limit limit);
}
