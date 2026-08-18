package ru.petstore.customer.service;

import ru.petstore.common.reference.ReferenceKind;

/** The customer reference tables; each one is backed by the cache bean it names here. */
public enum ReferenceType implements ReferenceKind {

    CITY,
    CUSTOMER_STATUS
}
