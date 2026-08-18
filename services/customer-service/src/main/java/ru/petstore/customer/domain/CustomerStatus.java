package ru.petstore.customer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import ru.petstore.common.reference.ReferenceEntity;

/** Customer status reference table. */
@Entity
@Table(name = "customer_status")
public class CustomerStatus extends ReferenceEntity {
}
