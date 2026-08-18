package ru.petstore.inventory.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.petstore.inventory.domain.StockItem;

public interface StockItemRepository extends JpaRepository<StockItem, UUID> {

    @EntityGraph(attributePaths = "warehouse")
    Optional<StockItem> findByProductId(UUID productId);

    List<StockItem> findAllByProductIdIn(Collection<UUID> productIds);
}
