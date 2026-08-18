package ru.petstore.catalog.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import ru.petstore.common.reference.ReferenceEntity;

/** Product category. */
@Entity
@Table(name = "category")
public class Category extends ReferenceEntity {
}
