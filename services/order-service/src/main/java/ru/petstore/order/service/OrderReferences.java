package ru.petstore.order.service;

import org.springframework.stereotype.Component;
import ru.petstore.common.reference.ReferenceDataService;
import ru.petstore.common.reference.ReferenceItem;
import ru.petstore.order.domain.DeliveryType;
import ru.petstore.order.domain.OrderStatus;
import ru.petstore.order.domain.OrderStatusCode;
import ru.petstore.order.domain.PaymentMethod;
import ru.petstore.order.repository.DeliveryTypeRepository;
import ru.petstore.order.repository.OrderStatusRepository;
import ru.petstore.order.repository.PaymentMethodRepository;

@Component
public class OrderReferences {

    private final ReferenceDataService referenceDataService;
    private final OrderStatusRepository orderStatusRepository;
    private final DeliveryTypeRepository deliveryTypeRepository;
    private final PaymentMethodRepository paymentMethodRepository;

    public OrderReferences(ReferenceDataService referenceDataService,
                           OrderStatusRepository orderStatusRepository,
                           DeliveryTypeRepository deliveryTypeRepository,
                           PaymentMethodRepository paymentMethodRepository) {
        this.referenceDataService = referenceDataService;
        this.orderStatusRepository = orderStatusRepository;
        this.deliveryTypeRepository = deliveryTypeRepository;
        this.paymentMethodRepository = paymentMethodRepository;
    }

    public ReferenceItem statusItem(OrderStatusCode code) {
        return referenceDataService.getRequired(ReferenceType.ORDER_STATUS, code.code());
    }

    public OrderStatus status(ReferenceItem item) {
        return orderStatusRepository.getReferenceById(item.id());
    }

    public ReferenceItem deliveryTypeItem(String code) {
        return referenceDataService.getRequired(ReferenceType.DELIVERY_TYPE, code);
    }

    public DeliveryType deliveryType(ReferenceItem item) {
        return deliveryTypeRepository.getReferenceById(item.id());
    }

    public ReferenceItem paymentMethodItem(String code) {
        return referenceDataService.getRequired(ReferenceType.PAYMENT_METHOD, code);
    }

    public PaymentMethod paymentMethod(ReferenceItem item) {
        return paymentMethodRepository.getReferenceById(item.id());
    }
}
