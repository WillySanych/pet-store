package ru.petstore.order.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import ru.petstore.common.reference.ReferenceEntity;

@Entity
@Table(name = "order_status")
public class OrderStatus extends ReferenceEntity {
}
