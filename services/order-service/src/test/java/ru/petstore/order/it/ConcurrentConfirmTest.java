package ru.petstore.order.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.petstore.order.domain.CustomerOrder;
import ru.petstore.order.domain.DeliveryAddress;
import ru.petstore.order.outbox.OrderEventPayload;
import ru.petstore.order.repository.DeliveryTypeRepository;
import ru.petstore.order.repository.OrderRepository;
import ru.petstore.order.repository.OrderStatusRepository;
import ru.petstore.order.repository.OutboxRepository;
import ru.petstore.order.repository.PaymentMethodRepository;
import ru.petstore.order.service.OrderService;

@SpringBootTest(properties = {
        "petstore.order.outbox-poll-interval=PT1H",
        "spring.kafka.admin.auto-create=false"
})
class ConcurrentConfirmTest extends AbstractPostgresTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OrderStatusRepository orderStatusRepository;

    @Autowired
    private DeliveryTypeRepository deliveryTypeRepository;

    @Autowired
    private PaymentMethodRepository paymentMethodRepository;

    @Test
    @DisplayName("Два параллельных подтверждения дают одно событие, а не два")
    void twoConcurrentConfirmsProduceOneEvent() throws Exception {
        UUID orderId = savedOrder();

        var barrier = new CyclicBarrier(2);
        Callable<Void> confirm = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            orderService.confirm(orderId);
            return null;
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Void>> results = pool.invokeAll(List.of(confirm, confirm));
            int succeeded = 0;
            for (Future<Void> result : results) {
                try {
                    result.get(20, TimeUnit.SECONDS);
                    succeeded++;
                } catch (Exception lost) {
                    assertThat(lost).hasStackTraceContaining("Optimistic");
                }
            }

            assertThat(succeeded).isPositive();
            assertThat(outboxRepository.findByAggregateIdOrderByCreatedAtAsc(orderId))
                    .singleElement()
                    .satisfies(message ->
                            assertThat(message.getType()).isEqualTo(OrderEventPayload.ORDER_CONFIRMED));
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID savedOrder() {
        CustomerOrder order = new CustomerOrder();
        order.setId(UUID.randomUUID());
        order.setCustomerId(UUID.randomUUID());
        order.setCustomerEmail("ivan@example.com");
        order.setStatus(orderStatusRepository.findAll().stream()
                .filter(status -> status.getCode().equals("NEW"))
                .findFirst()
                .orElseThrow());
        order.setDeliveryType(deliveryTypeRepository.findAll().getFirst());
        order.setPaymentMethod(paymentMethodRepository.findAll().getFirst());
        order.setAddress(new DeliveryAddress("MSK", "Москва", "Тверская", "1", "10", "125009"));
        order.addItem(UUID.randomUUID(), "Корм", new BigDecimal("100.00"), 1);
        return orderRepository.saveAndFlush(order).getId();
    }
}
