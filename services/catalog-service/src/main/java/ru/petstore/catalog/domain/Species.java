package ru.petstore.catalog.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import ru.petstore.common.reference.ReferenceEntity;

/** The animal species a product is meant for. */
@Entity
@Table(name = "species")
public class Species extends ReferenceEntity {
}
