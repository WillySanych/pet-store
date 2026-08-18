package ru.petstore.inventory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import ru.petstore.common.reference.ReferenceEntity;

/** The lifecycle state of a reservation; the codes are listed in {@link ReservationStatusCode}. */
@Entity
@Table(name = "reservation_status")
public class ReservationStatus extends ReferenceEntity {
}
