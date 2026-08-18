package ru.petstore.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.petstore.inventory.domain.ReservationStatus;

public interface ReservationStatusRepository extends JpaRepository<ReservationStatus, Long> {
}
