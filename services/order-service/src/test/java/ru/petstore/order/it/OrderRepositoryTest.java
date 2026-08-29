package ru.petstore.order.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import ru.petstore.order.domain.CustomerOrder;
import ru.petstore.order.domain.DeliveryAddress;
import ru.petstore.order.domain.DeliveryType;
import ru.petstore.order.domain.OrderStatus;
import ru.petstore.order.domain.OrderStatusHistory;
import ru.petstore.order.domain.OutboxMessage;
import ru.petstore.order.domain.PaymentMethod;
import ru.petstore.order.repository.DeliveryTypeRepository;
import ru.petstore.order.repository.OrderRepository;
import ru.petstore.order.repository.OrderStatusHistoryRepository;
import ru.petstore.order.repository.OrderStatusRepository;
import ru.petstore.order.repository.OutboxRepository;
import ru.petstore.order.repository.PaymentMethodRepository;

@SpringBootTest(properties = {
        "petstore.order.outbox-poll-interval=PT1H",
        "spring.kafka.admin.auto-create=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class OrderRepositoryTest extends AbstractPostgresTest {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderStatusHistoryRepository historyRepository;
    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private OrderStatusRepository orderStatusRepository;
    @Autowired
    private DeliveryTypeRepository deliveryTypeRepository;
    @Autowired
    private PaymentMethodRepository paymentMethodRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Заказ сохраняется с позициями и читается одним запросом вместе с ними")
    void orderIsSavedWithItems() {
        CustomerOrder order = order(UUID.randomUUID(), null);
        order.addItem(UUID.randomUUID(), "Корм", new BigDecimal("199.90"), 2);
        orderRepository.saveAndFlush(order);
        entityManager.clear();

        CustomerOrder found = orderRepository.findWithItemsById(order.getId()).orElseThrow();

        assertThat(found.getItems()).hasSize(1);
        assertThat(found.getTotalAmount()).isEqualByComparingTo("399.80");
        assertThat(found.getAddress().getCityCode()).isEqualTo("MSK");
        assertThat(found.getStatus().getCode()).isEqualTo("NEW");
    }

    @Test
    @DisplayName("Идентификатор задаётся до сохранения и не подменяется")
    void assignedIdSurvivesTheSave() {
        UUID id = UUID.randomUUID();

        orderRepository.saveAndFlush(order(id, null));

        assertThat(orderRepository.findById(id)).isPresent();
    }

    @Test
    @DisplayName("Повторный ключ того же клиента отбивается уникальным индексом")
    void duplicateIdempotencyKeyIsRejected() {
        String key = "key-" + UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        orderRepository.saveAndFlush(order(UUID.randomUUID(), customerId, key));

        assertThatThrownBy(() ->
                orderRepository.saveAndFlush(order(UUID.randomUUID(), customerId, key)))
                .isInstanceOf(DataIntegrityViolationException.class);
        entityManager.clear();
    }

    @Test
    @DisplayName("Тот же ключ у другого клиента живёт своей жизнью: индекс составной")
    void sameKeyOfAnotherCustomerIsAllowed() {
        String key = "key-" + UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        orderRepository.saveAndFlush(order(UUID.randomUUID(), first, key));
        orderRepository.saveAndFlush(order(UUID.randomUUID(), second, key));
        entityManager.clear();

        assertThat(orderRepository.findByCustomerIdAndIdempotencyKey(first, key)).isPresent();
        assertThat(orderRepository.findByCustomerIdAndIdempotencyKey(second, key)).isPresent();
        assertThat(orderRepository.findByCustomerIdAndIdempotencyKey(first, key).orElseThrow().getId())
                .isNotEqualTo(orderRepository.findByCustomerIdAndIdempotencyKey(second, key)
                        .orElseThrow().getId());
    }

    @Test
    @DisplayName("Публикатору отдаются только неопубликованные сообщения, старые первыми")
    void unpublishedMessagesComeOldestFirst() {
        UUID orderId = UUID.randomUUID();
        outboxRepository.saveAndFlush(OutboxMessage.of(orderId, "order-events", "ORDER_CONFIRMED",
                "{}", "trace-1"));
        OutboxMessage published = outboxRepository.saveAndFlush(
                OutboxMessage.of(orderId, "order-events", "ORDER_CANCELLED", "{}", null));
        outboxRepository.markPublished(published.getId(), Instant.now());
        entityManager.clear();

        List<OutboxMessage> pending = outboxRepository.findUnpublished(10, Limit.of(10)).stream()
                .filter(message -> message.getAggregateId().equals(orderId))
                .toList();

        assertThat(pending).singleElement()
                .satisfies(message -> assertThat(message.getType()).isEqualTo("ORDER_CONFIRMED"));
    }

    @Test
    @DisplayName("Сообщение, исчерпавшее попытки, публикатору больше не отдаётся")
    void exhaustedMessageIsLeftOut() {
        UUID orderId = UUID.randomUUID();
        OutboxMessage stuck = outboxRepository.saveAndFlush(OutboxMessage.of(
                orderId, "order-events", "ORDER_CONFIRMED", "{}", null));
        for (int attempt = 0; attempt < 3; attempt++) {
            outboxRepository.markAttempted(stuck.getId());
        }
        entityManager.clear();

        assertThat(outboxRepository.findUnpublished(3, Limit.of(10)))
                .noneMatch(message -> message.getId().equals(stuck.getId()));
        assertThat(outboxRepository.findUnpublished(4, Limit.of(10)))
                .anyMatch(message -> message.getId().equals(stuck.getId()));
    }

    @Test
    @DisplayName("Отметка о публикации ставится один раз и считает попытку")
    void markPublishedIsAppliedOnce() {
        OutboxMessage message = outboxRepository.saveAndFlush(OutboxMessage.of(
                UUID.randomUUID(), "order-events", "ORDER_CONFIRMED", "{}", null));

        assertThat(outboxRepository.markPublished(message.getId(), Instant.now())).isOne();
        assertThat(outboxRepository.markPublished(message.getId(), Instant.now())).isZero();
        entityManager.clear();

        OutboxMessage stored = outboxRepository.findById(message.getId()).orElseThrow();
        assertThat(stored.getPublishedAt()).isNotNull();
        assertThat(stored.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("История статусов читается по возрастанию времени")
    void historyIsOrdered() {
        CustomerOrder order = orderRepository.saveAndFlush(order(UUID.randomUUID(), null));
        historyRepository.saveAndFlush(history(order, status("NEW")));
        historyRepository.saveAndFlush(history(order, status("CONFIRMED")));
        entityManager.clear();

        List<OrderStatusHistory> history = historyRepository.findByOrderIdOrderByChangedAtAsc(order.getId());

        assertThat(history).hasSize(2);
        assertThat(history.get(0).getStatus().getCode()).isEqualTo("NEW");
        assertThat(history.get(1).getStatus().getCode()).isEqualTo("CONFIRMED");
    }

    @Test
    @Transactional
    @DisplayName("Страница заказов клиента не превращается в запрос на позицию")
    void listingDoesNotQueryItemsPerOrder() {
        UUID customerId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            CustomerOrder order = order(UUID.randomUUID(), null);
            order.setCustomerId(customerId);
            order.addItem(UUID.randomUUID(), "Корм", new BigDecimal("100.00"), 1);
            orderRepository.saveAndFlush(order);
        }
        entityManager.clear();

        Statistics statistics = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var page = orderRepository.findByCustomerId(customerId,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));
        page.getContent().forEach(order -> assertThat(order.getItems()).hasSize(1));

        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }

    private CustomerOrder order(UUID id, String idempotencyKey) {
        return order(id, UUID.randomUUID(), idempotencyKey);
    }

    private CustomerOrder order(UUID id, UUID customerId, String idempotencyKey) {
        CustomerOrder order = new CustomerOrder();
        order.setId(id);
        order.setCustomerId(customerId);
        order.setCustomerEmail("ivan@example.com");
        order.setIdempotencyKey(idempotencyKey);
        order.setStatus(status("NEW"));
        order.setDeliveryType(deliveryType());
        order.setPaymentMethod(paymentMethod());
        order.setAddress(new DeliveryAddress("MSK", "Москва", "Тверская", "1", "10", "125009"));
        return order;
    }

    private static OrderStatusHistory history(CustomerOrder order, OrderStatus status) {
        OrderStatusHistory row = new OrderStatusHistory();
        row.setOrder(order);
        row.setStatus(status);
        return row;
    }

    private OrderStatus status(String code) {
        return orderStatusRepository.findAll().stream()
                .filter(status -> status.getCode().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private DeliveryType deliveryType() {
        return deliveryTypeRepository.findAll().getFirst();
    }

    private PaymentMethod paymentMethod() {
        return paymentMethodRepository.findAll().getFirst();
    }
}
