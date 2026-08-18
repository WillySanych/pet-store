package ru.petstore.customer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import ru.petstore.common.reference.ReferenceEntity;

/** City an address belongs to. */
@Entity
@Table(name = "city")
public class City extends ReferenceEntity {
}
