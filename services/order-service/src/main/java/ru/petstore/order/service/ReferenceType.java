package ru.petstore.order.service;

import ru.petstore.common.reference.ReferenceKind;

public enum ReferenceType implements ReferenceKind {
    ORDER_STATUS,
    DELIVERY_TYPE,
    PAYMENT_METHOD
}
