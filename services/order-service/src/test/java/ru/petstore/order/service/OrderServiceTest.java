package ru.petstore.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.petstore.common.reference.ReferenceItem;
import ru.petstore.common.web.ConcurrentChangeException;
import ru.petstore.common.web.ResourceNotFoundException;
import ru.petstore.order.client.CatalogClient;
import ru.petstore.order.client.CatalogProduct;
import ru.petstore.order.client.CustomerClient;
import ru.petstore.order.client.DeliveryTarget;
import ru.petstore.order.client.InventoryClient;
import ru.petstore.order.client.ReserveResult;
import ru.petstore.order.client.UpstreamExecutor;
import ru.petstore.order.client.UpstreamNotFoundException;
import ru.petstore.order.client.UpstreamUnavailableException;
import ru.petstore.order.domain.CustomerOrder;
import ru.petstore.order.domain.DeliveryAddress;
import ru.petstore.order.domain.DeliveryType;
import ru.petstore.order.domain.OrderStatus;
import ru.petstore.order.domain.OrderStatusCode;
import ru.petstore.order.domain.OrderStatusHistory;
import ru.petstore.order.domain.PaymentMethod;
import ru.petstore.order.outbox.OrderEventPayload;
import ru.petstore.order.outbox.OutboxWriter;
import ru.petstore.order.repository.OrderRepository;
import ru.petstore.order.repository.OrderStatusHistoryRepository;
import ru.petstore.order.web.dto.OrderItemRequest;
import ru.petstore.order.web.dto.OrderRequest;
import ru.petstore.order.web.dto.OrderResponse;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID OTHER_PRODUCT = UUID.randomUUID();

    private static final ReferenceItem NEW_STATUS = new ReferenceItem(1L, "NEW", "Новый");
    private static final ReferenceItem CONFIRMED_STATUS = new ReferenceItem(2L, "CONFIRMED", "Подтверждён");
    private static final ReferenceItem CANCELLED_STATUS = new ReferenceItem(3L, "CANCELLED", "Отменён");
    private static final ReferenceItem COURIER = new ReferenceItem(10L, "COURIER", "Курьер");
    private static final ReferenceItem CARD = new ReferenceItem(20L, "CARD", "Карта");

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderStatusHistoryRepository historyRepository;
    @Mock
    private OrderReferences references;
    @Mock
    private OutboxWriter outbox;
    @Mock
    private CatalogClient catalogClient;
    @Mock
    private InventoryClient inventoryClient;
    @Mock
    private CustomerClient customerClient;
    @Mock
    private PlatformTransactionManager transactionManager;

    private UpstreamExecutor executor;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        executor = new UpstreamExecutor();
        orderService = new OrderService(orderRepository, historyRepository, references, outbox,
                catalogClient, inventoryClient, customerClient, executor,
                new TransactionTemplate(transactionManager));

        lenient().when(references.statusItem(OrderStatusCode.NEW)).thenReturn(NEW_STATUS);
        lenient().when(references.statusItem(OrderStatusCode.CONFIRMED)).thenReturn(CONFIRMED_STATUS);
        lenient().when(references.statusItem(OrderStatusCode.CANCELLED)).thenReturn(CANCELLED_STATUS);
        lenient().when(references.deliveryTypeItem("COURIER")).thenReturn(COURIER);
        lenient().when(references.paymentMethodItem("CARD")).thenReturn(CARD);
        lenient().when(references.status(any())).thenAnswer(call -> status(call.getArgument(0)));
        lenient().when(references.deliveryType(any())).thenReturn(new DeliveryType());
        lenient().when(references.paymentMethod(any())).thenReturn(new PaymentMethod());
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    @DisplayName("Заказ оформляется: цены из каталога, адрес из customer, остаток удержан")
    void orderIsPlaced() {
        upstreamsAnswer();
        when(inventoryClient.reserve(any(), any())).thenReturn(ReserveResult.held());

        OrderCreation creation = orderService.create(request(new OrderItemRequest(PRODUCT, 2)), null);

        assertThat(creation.created()).isTrue();
        OrderResponse order = creation.order();
        assertThat(order.customerId()).isEqualTo(CUSTOMER);
        assertThat(order.customerEmail()).isEqualTo("ivan@example.com");
        assertThat(order.status().code()).isEqualTo("NEW");
        assertThat(order.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.productId()).isEqualTo(PRODUCT);
                    assertThat(item.productName()).isEqualTo("Корм");
                    assertThat(item.quantity()).isEqualTo(2);
                    assertThat(item.amount()).isEqualByComparingTo("200.00");
                });
        assertThat(order.totalAmount()).isEqualByComparingTo("200.00");
        assertThat(order.address().city().code()).isEqualTo("MSK");

        verify(orderRepository).saveAndFlush(any(CustomerOrder.class));
        verify(historyRepository).save(any(OrderStatusHistory.class));
    }

    @Test
    @DisplayName("Резерв запрашивается на тот же идентификатор, что и у сохранённого заказа")
    void reserveUsesTheOrderId() {
        upstreamsAnswer();
        when(inventoryClient.reserve(any(), any())).thenReturn(ReserveResult.held());

        OrderCreation creation = orderService.create(request(new OrderItemRequest(PRODUCT, 1)), null);

        ArgumentCaptor<UUID> reserved = ArgumentCaptor.forClass(UUID.class);
        verify(inventoryClient).reserve(reserved.capture(), eq(Map.of(PRODUCT, 1)));
        assertThat(reserved.getValue()).isEqualTo(creation.order().id());
    }

    @Test
    @DisplayName("Две строки одного товара складываются в одну позицию")
    void duplicateLinesAreMerged() {
        upstreamsAnswer();
        when(inventoryClient.reserve(any(), any())).thenReturn(ReserveResult.held());

        OrderCreation creation = orderService.create(
                request(new OrderItemRequest(PRODUCT, 2), new OrderItemRequest(PRODUCT, 3)), null);

        assertThat(creation.order().items()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualTo(5));
        verify(inventoryClient).reserve(any(), eq(Map.of(PRODUCT, 5)));
    }

    @Test
    @DisplayName("Повтор с тем же Idempotency-Key отдаёт созданный заказ и не ходит в апстримы")
    void repeatedKeyReturnsTheSameOrder() {
        CustomerOrder existing = order(OrderStatusCode.NEW);
        when(orderRepository.findByCustomerIdAndIdempotencyKey(CUSTOMER, "key-1")).thenReturn(Optional.of(existing));
        when(transactionManager.getTransaction(any())).thenReturn(null);

        OrderCreation creation = orderService.create(request(new OrderItemRequest(PRODUCT, 1)), "key-1");

        assertThat(creation.created()).isFalse();
        assertThat(creation.order().id()).isEqualTo(existing.getId());
        verifyNoInteractions(catalogClient, customerClient, inventoryClient);
    }

    @Test
    @DisplayName("Тот же ключ у другого клиента — свой заказ, а не чужой")
    void keyOfAnotherCustomerIsNotAReplay() {
        upstreamsAnswer();
        when(inventoryClient.reserve(any(), any())).thenReturn(ReserveResult.held());
        when(orderRepository.findByCustomerIdAndIdempotencyKey(CUSTOMER, "key-1"))
                .thenReturn(Optional.empty());

        OrderCreation creation = orderService.create(request(new OrderItemRequest(PRODUCT, 1)), "key-1");

        assertThat(creation.created()).isTrue();
        verify(orderRepository).saveAndFlush(any(CustomerOrder.class));
    }

    @Test
    @DisplayName("Нет остатка — 422 со списком товаров, заказ не сохраняется")
    void outOfStockIsRejected() {
        upstreamsAnswer();
        when(inventoryClient.reserve(any(), any())).thenReturn(ReserveResult.refused(List.of(PRODUCT)));

        assertThatThrownBy(() -> orderService.create(request(new OrderItemRequest(PRODUCT, 2)), null))
                .isInstanceOf(OrderRejectedException.class)
                .hasMessageContaining(PRODUCT.toString())
                .extracting(e -> ((OrderRejectedException) e).getCode()).isEqualTo("OUT_OF_STOCK");

        verify(orderRepository, never()).saveAndFlush(any());
        verify(inventoryClient, never()).releaseQuietly(any());
    }

    @Test
    @DisplayName("Снятый с продажи товар — отказ до обращения к складу")
    void inactiveProductIsRejectedBeforeReserve() {
        when(catalogClient.products(any())).thenReturn(Map.of(PRODUCT,
                new CatalogProduct(PRODUCT, "Корм", new BigDecimal("100.00"), false)));
        when(customerClient.deliveryTarget(any(), any())).thenReturn(deliveryTarget("ACTIVE"));

        assertThatThrownBy(() -> orderService.create(request(new OrderItemRequest(PRODUCT, 1)), null))
                .isInstanceOf(OrderRejectedException.class)
                .extracting(e -> ((OrderRejectedException) e).getCode()).isEqualTo("PRODUCT_UNAVAILABLE");

        verifyNoInteractions(inventoryClient);
    }

    @Test
    @DisplayName("Неизвестный каталогу товар — тот же отказ")
    void unknownProductIsRejected() {
        when(catalogClient.products(any())).thenReturn(Map.of(PRODUCT,
                new CatalogProduct(PRODUCT, "Корм", new BigDecimal("100.00"), true)));
        when(customerClient.deliveryTarget(any(), any())).thenReturn(deliveryTarget("ACTIVE"));

        assertThatThrownBy(() -> orderService.create(
                request(new OrderItemRequest(PRODUCT, 1), new OrderItemRequest(OTHER_PRODUCT, 1)), null))
                .isInstanceOf(OrderRejectedException.class)
                .hasMessageContaining(OTHER_PRODUCT.toString());

        verifyNoInteractions(inventoryClient);
    }

    @Test
    @DisplayName("Заблокированный клиент — отказ: решение принимает order-service, а не customer")
    void blockedCustomerIsRejected() {
        upstreamsAnswer("BLOCKED");

        assertThatThrownBy(() -> orderService.create(request(new OrderItemRequest(PRODUCT, 1)), null))
                .isInstanceOf(OrderRejectedException.class)
                .extracting(e -> ((OrderRejectedException) e).getCode()).isEqualTo("CUSTOMER_BLOCKED");

        verifyNoInteractions(inventoryClient);
    }

    @Test
    @DisplayName("Клиент или адрес не найдены — отказ, а не 500")
    void missingDeliveryTargetIsRejected() {
        when(catalogClient.products(any())).thenReturn(catalog());
        when(customerClient.deliveryTarget(any(), any()))
                .thenThrow(new UpstreamNotFoundException("Customer has no delivery address", null));

        assertThatThrownBy(() -> orderService.create(request(new OrderItemRequest(PRODUCT, 1)), null))
                .isInstanceOf(OrderRejectedException.class)
                .extracting(e -> ((OrderRejectedException) e).getCode())
                .isEqualTo("DELIVERY_TARGET_NOT_FOUND");

        verifyNoInteractions(inventoryClient);
    }

    @Test
    @DisplayName("Недоступный каталог пробрасывается наверх, склад не трогается")
    void unavailableCatalogIsPropagated() {
        when(catalogClient.products(any()))
                .thenThrow(new UpstreamUnavailableException("catalog", "timeout", null));
        lenient().when(customerClient.deliveryTarget(any(), any())).thenReturn(deliveryTarget("ACTIVE"));

        assertThatThrownBy(() -> orderService.create(request(new OrderItemRequest(PRODUCT, 1)), null))
                .isInstanceOf(UpstreamUnavailableException.class);

        verify(inventoryClient, never()).reserve(any(), any());
    }

    @Test
    @DisplayName("Падение сохранения компенсируется освобождением резерва")
    void failedPersistReleasesTheHold() {
        upstreamsAnswer();
        when(inventoryClient.reserve(any(), any())).thenReturn(ReserveResult.held());
        when(transactionManager.getTransaction(any())).thenReturn(null);
        when(orderRepository.saveAndFlush(any())).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> orderService.create(request(new OrderItemRequest(PRODUCT, 1)), null))
                .isInstanceOf(IllegalStateException.class);

        verify(inventoryClient).releaseQuietly(any(UUID.class));
    }

    @Test
    @DisplayName("Проигранная гонка за ключ идемпотентности отдаёт чужой заказ и снимает свой резерв")
    void lostIdempotencyRaceReturnsTheWinner() {
        upstreamsAnswer();
        when(inventoryClient.reserve(any(), any())).thenReturn(ReserveResult.held());
        when(transactionManager.getTransaction(any())).thenReturn(null);
        when(orderRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_customer_order_idempotency\"",
                new RuntimeException("uq_customer_order_idempotency")));
        CustomerOrder winner = order(OrderStatusCode.NEW);
        when(orderRepository.findByCustomerIdAndIdempotencyKey(CUSTOMER, "key-1")).thenReturn(Optional.empty()).thenReturn(Optional.of(winner));

        OrderCreation creation = orderService.create(request(new OrderItemRequest(PRODUCT, 1)), "key-1");

        assertThat(creation.created()).isFalse();
        assertThat(creation.order().id()).isEqualTo(winner.getId());
        verify(inventoryClient).releaseQuietly(any(UUID.class));
    }

    @Test
    @DisplayName("Гонка без победителя — 409: повторить, а не выдумывать ответ")
    void raceWithoutWinnerIsAConflict() {
        upstreamsAnswer();
        when(inventoryClient.reserve(any(), any())).thenReturn(ReserveResult.held());
        when(transactionManager.getTransaction(any())).thenReturn(null);
        when(orderRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "uq_customer_order_idempotency", new RuntimeException("uq_customer_order_idempotency")));
        when(orderRepository.findByCustomerIdAndIdempotencyKey(CUSTOMER, "key-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.create(request(new OrderItemRequest(PRODUCT, 1)), "key-1"))
                .isInstanceOf(ConcurrentChangeException.class);
    }

    @Test
    @DisplayName("Подтверждение переводит в CONFIRMED, пишет историю и событие в outbox")
    void confirmMovesToConfirmed() {
        CustomerOrder order = order(OrderStatusCode.NEW);
        when(orderRepository.findWithItemsById(order.getId())).thenReturn(Optional.of(order));

        OrderResponse response = orderService.confirm(order.getId());

        assertThat(response.status().code()).isEqualTo("CONFIRMED");
        assertThat(order.getStatus().getCode()).isEqualTo("CONFIRMED");
        verify(historyRepository).save(any(OrderStatusHistory.class));
        verify(outbox).append(order, OrderEventPayload.ORDER_CONFIRMED);
    }

    @Test
    @DisplayName("Повторное подтверждение ничего не меняет и второго события не даёт")
    void confirmIsIdempotent() {
        CustomerOrder order = order(OrderStatusCode.CONFIRMED);
        when(orderRepository.findWithItemsById(order.getId())).thenReturn(Optional.of(order));

        assertThat(orderService.confirm(order.getId()).status().code()).isEqualTo("CONFIRMED");

        verifyNoInteractions(outbox);
        verifyNoInteractions(historyRepository);
    }

    @Test
    @DisplayName("Отменённый заказ подтвердить нельзя")
    void cancelledOrderCannotBeConfirmed() {
        CustomerOrder order = order(OrderStatusCode.CANCELLED);
        when(orderRepository.findWithItemsById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.confirm(order.getId()))
                .isInstanceOf(OrderStateException.class);

        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("Отмена переводит в CANCELLED и кладёт ORDER_CANCELLED в outbox")
    void cancelMovesToCancelled() {
        CustomerOrder order = order(OrderStatusCode.NEW);
        when(orderRepository.findWithItemsById(order.getId())).thenReturn(Optional.of(order));

        assertThat(orderService.cancel(order.getId()).status().code()).isEqualTo("CANCELLED");

        verify(outbox).append(order, OrderEventPayload.ORDER_CANCELLED);
    }

    @Test
    @DisplayName("Подтверждённый заказ не отменяется: остаток уже списан")
    void confirmedOrderCannotBeCancelled() {
        CustomerOrder order = order(OrderStatusCode.CONFIRMED);
        when(orderRepository.findWithItemsById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancel(order.getId()))
                .isInstanceOf(OrderStateException.class);

        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("Несуществующий заказ — 404")
    void missingOrderIsNotFound() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findWithItemsById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.get(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Сортировка по неизвестному полю — 400, а не 500 из глубины Spring Data")
    void unknownSortIsRejected() {
        assertThatThrownBy(() -> orderService.listByCustomer(CUSTOMER,
                PageRequest.of(0, 20, Sort.by("nonsense"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonsense");

        verifyNoInteractions(orderRepository);
    }

    private void upstreamsAnswer() {
        upstreamsAnswer("ACTIVE");
    }

    private void upstreamsAnswer(String customerStatus) {
        when(catalogClient.products(any())).thenReturn(catalog());
        when(customerClient.deliveryTarget(any(), any())).thenReturn(deliveryTarget(customerStatus));
        lenient().when(transactionManager.getTransaction(any())).thenReturn(null);
    }

    private static Map<UUID, CatalogProduct> catalog() {
        return Map.of(PRODUCT, new CatalogProduct(PRODUCT, "Корм", new BigDecimal("100.00"), true));
    }

    private static DeliveryTarget deliveryTarget(String customerStatus) {
        return new DeliveryTarget(
                new DeliveryTarget.Customer(CUSTOMER, "ivan@example.com", "Иван", "Петров",
                        new DeliveryTarget.Reference(customerStatus, customerStatus)),
                new DeliveryTarget.Address(UUID.randomUUID(),
                        new DeliveryTarget.Reference("MSK", "Москва"),
                        "Тверская", "1", "10", "125009"));
    }

    private static OrderRequest request(OrderItemRequest... items) {
        return new OrderRequest(CUSTOMER, null, "COURIER", "CARD", List.of(items));
    }

    private static OrderStatus status(ReferenceItem item) {
        OrderStatus status = new OrderStatus();
        status.setId(item.id());
        status.setCode(item.code());
        status.setName(item.name());
        return status;
    }

    private static CustomerOrder order(OrderStatusCode code) {
        CustomerOrder order = new CustomerOrder();
        order.setId(UUID.randomUUID());
        order.setCustomerId(CUSTOMER);
        order.setCustomerEmail("ivan@example.com");
        order.setStatus(status(switch (code) {
            case NEW -> NEW_STATUS;
            case CONFIRMED -> CONFIRMED_STATUS;
            case CANCELLED -> CANCELLED_STATUS;
        }));
        order.setDeliveryType(reference(new DeliveryType(), COURIER));
        order.setPaymentMethod(reference(new PaymentMethod(), CARD));
        order.setAddress(new DeliveryAddress("MSK", "Москва", "Тверская", "1", "10", "125009"));
        order.addItem(PRODUCT, "Корм", new BigDecimal("100.00"), 1);
        return order;
    }

    private static <T extends ru.petstore.common.reference.ReferenceEntity> T reference(T entity,
                                                                                        ReferenceItem item) {
        entity.setId(item.id());
        entity.setCode(item.code());
        entity.setName(item.name());
        return entity;
    }
}
