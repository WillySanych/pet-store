package ru.petstore.order.service;

import ru.petstore.order.web.dto.OrderResponse;

public record OrderCreation(OrderResponse order, boolean created) {
}
