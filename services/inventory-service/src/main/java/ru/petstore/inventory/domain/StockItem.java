package ru.petstore.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * What is on hand for one product, and how much of it is held by open reservations.
 */
@Entity
@Table(name = "stock_item")
public class StockItem {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "product_id", nullable = false, unique = true)
    private UUID productId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    /** On hand, reservations included. */
    @Column(nullable = false)
    private int quantity;

    /** Held by reservations in state {@code ACTIVE}. */
    @Column(nullable = false)
    private int reserved;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public int available() {
        return quantity - reserved;
    }

    public void reserve(int amount) {
        if (amount > available()) {
            throw new IllegalStateException("Cannot reserve " + amount + " of product " + productId
                    + ": only " + available() + " available");
        }
        reserved += amount;
    }

    public void releaseReserved(int amount) {
        if (amount > reserved) {
            throw new IllegalStateException("Cannot release " + amount + " of product " + productId
                    + ": only " + reserved + " reserved");
        }
        reserved -= amount;
    }

    /** Ships the held amount: it leaves both {@code reserved} and {@code quantity}. */
    public void commitReserved(int amount) {
        releaseReserved(amount);
        quantity -= amount;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public void setWarehouse(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public int getReserved() {
        return reserved;
    }

    public void setReserved(int reserved) {
        this.reserved = reserved;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
