package ru.petstore.order.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import ru.petstore.common.reference.ReferenceEntity;

@Entity
@Table(name = "payment_method")
public class PaymentMethod extends ReferenceEntity {
}
