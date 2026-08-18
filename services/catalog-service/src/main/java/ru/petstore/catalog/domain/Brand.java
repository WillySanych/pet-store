package ru.petstore.catalog.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import ru.petstore.common.reference.ReferenceEntity;

/** Product brand. */
@Entity
@Table(name = "brand")
public class Brand extends ReferenceEntity {
}
