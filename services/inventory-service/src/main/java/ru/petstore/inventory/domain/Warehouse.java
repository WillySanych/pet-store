package ru.petstore.inventory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import ru.petstore.common.reference.ReferenceEntity;

/** The warehouse a stock item is kept at. */
@Entity
@Table(name = "warehouse")
public class Warehouse extends ReferenceEntity {
}
