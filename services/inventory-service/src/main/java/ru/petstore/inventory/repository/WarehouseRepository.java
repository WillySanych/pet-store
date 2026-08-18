package ru.petstore.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.petstore.inventory.domain.Warehouse;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {
}
