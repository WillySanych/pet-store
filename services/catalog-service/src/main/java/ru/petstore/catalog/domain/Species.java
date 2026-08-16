package ru.petstore.catalog.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** The animal species a product is meant for. */
@Entity
@Table(name = "species")
public class Species extends ReferenceEntity {
}
