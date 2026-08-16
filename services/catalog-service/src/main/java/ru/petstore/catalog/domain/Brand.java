package ru.petstore.catalog.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Product brand.
 */
@Entity
@Table(name = "brand")
public class Brand extends ReferenceEntity {
}
