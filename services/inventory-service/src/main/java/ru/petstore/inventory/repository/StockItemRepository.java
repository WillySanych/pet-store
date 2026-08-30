package ru.petstore.inventory.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.petstore.inventory.domain.StockItem;

public interface StockItemRepository extends JpaRepository<StockItem, UUID> {

    @EntityGraph(attributePaths = "warehouse")
    Optional<StockItem> findByProductId(UUID productId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE inventory.stock_item
               SET reserved = reserved + :amount,
                   updated_at = clock_timestamp()
             WHERE product_id = :productId
               AND :amount > 0
               AND quantity - reserved >= :amount
            """, nativeQuery = true)
    int reserveIfAvailable(@Param("productId") UUID productId, @Param("amount") int amount);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE inventory.stock_item
               SET reserved = reserved - :amount,
                   updated_at = clock_timestamp()
             WHERE product_id = :productId
               AND :amount > 0
               AND reserved >= :amount
            """, nativeQuery = true)
    int releaseIfReserved(@Param("productId") UUID productId, @Param("amount") int amount);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE inventory.stock_item
               SET quantity = quantity - :amount,
                   reserved = reserved - :amount,
                   updated_at = clock_timestamp()
             WHERE product_id = :productId
               AND :amount > 0
               AND reserved >= :amount
               AND quantity >= :amount
            """, nativeQuery = true)
    int commitIfReserved(@Param("productId") UUID productId, @Param("amount") int amount);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO inventory.stock_item AS current_stock
                   (id, product_id, warehouse_id, quantity, reserved, created_at, updated_at)
            VALUES (:id, :productId, :warehouseId, :quantity, 0,
                    clock_timestamp(), clock_timestamp())
            ON CONFLICT (product_id) DO UPDATE
               SET warehouse_id = EXCLUDED.warehouse_id,
                   quantity = EXCLUDED.quantity,
                   updated_at = clock_timestamp()
             WHERE current_stock.reserved <= EXCLUDED.quantity
            """, nativeQuery = true)
    int upsertQuantity(@Param("id") UUID id,
                       @Param("productId") UUID productId,
                       @Param("warehouseId") long warehouseId,
                       @Param("quantity") int quantity);
}
