package ru.petstore.order.service;

import io.github.resilience4j.ratelimiter.RateLimiter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import ru.petstore.order.domain.CustomerOrder;
import ru.petstore.order.domain.DeliveryAddress;
import ru.petstore.order.domain.OrderStatusCode;
import ru.petstore.order.domain.OrderStatusHistory;
import ru.petstore.order.outbox.OrderEventPayload;
import ru.petstore.order.outbox.OutboxWriter;
import ru.petstore.order.repository.OrderRepository;
import ru.petstore.order.repository.OrderStatusHistoryRepository;
import ru.petstore.order.web.dto.OrderItemRequest;
import ru.petstore.order.web.dto.OrderRequest;
import ru.petstore.order.web.dto.OrderResponse;
import ru.petstore.order.web.dto.OrderStatusHistoryResponse;
import ru.petstore.order.web.dto.PageResponse;
import ru.petstore.order.web.dto.ReferenceResponse;

@Service
public class OrderService {

    private static final SortedSet<String> SORTABLE = Collections.unmodifiableSortedSet(new TreeSet<>(
            List.of("createdAt", "updatedAt", "totalAmount")));

    private static final String IDEMPOTENCY_INDEX = "uq_customer_order_idempotency";
    private static final String BLOCKED = "BLOCKED";

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final OrderReferences references;
    private final OutboxWriter outbox;
    private final CatalogClient catalogClient;
    private final InventoryClient inventoryClient;
    private final CustomerClient customerClient;
    private final UpstreamExecutor executor;
    private final TransactionTemplate transactionTemplate;
    private final RateLimiter ordersRateLimiter;

    public OrderService(OrderRepository orderRepository,
                        OrderStatusHistoryRepository historyRepository,
                        OrderReferences references,
                        OutboxWriter outbox,
                        CatalogClient catalogClient,
                        InventoryClient inventoryClient,
                        CustomerClient customerClient,
                        UpstreamExecutor executor,
                        TransactionTemplate transactionTemplate,
                        RateLimiter ordersRateLimiter) {
        this.orderRepository = orderRepository;
        this.historyRepository = historyRepository;
        this.references = references;
        this.outbox = outbox;
        this.catalogClient = catalogClient;
        this.inventoryClient = inventoryClient;
        this.customerClient = customerClient;
        this.executor = executor;
        this.transactionTemplate = transactionTemplate;
        this.ordersRateLimiter = ordersRateLimiter;
    }

    public OrderCreation create(OrderRequest request, String idempotencyKey) {
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        return ordersRateLimiter.executeSupplier(() -> place(request, key));
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID id) {
        return OrderResponse.of(require(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> listByCustomer(UUID customerId, Pageable pageable) {
        checkSortable(pageable.getSort());
        return PageResponse.of(orderRepository.findByCustomerId(customerId, pageable), OrderResponse::of);
    }

    @Transactional(readOnly = true)
    public List<OrderStatusHistoryResponse> history(UUID id) {
        if (!orderRepository.existsById(id)) {
            throw ResourceNotFoundException.of("Order", id);
        }
        return historyRepository.findByOrderIdOrderByChangedAtAsc(id).stream()
                .map(OrderStatusHistoryResponse::of)
                .toList();
    }

    @Transactional
    public OrderResponse confirm(UUID id) {
        return move(id, OrderStatusCode.CONFIRMED, OrderEventPayload.ORDER_CONFIRMED, "confirmed");
    }

    @Transactional
    public OrderResponse cancel(UUID id) {
        return move(id, OrderStatusCode.CANCELLED, OrderEventPayload.ORDER_CANCELLED, "cancelled");
    }

    private OrderResponse move(UUID id, OrderStatusCode target, String eventType, String attempted) {
        CustomerOrder order = require(id);
        if (order.hasStatus(target)) {
            return OrderResponse.of(order);
        }
        if (!order.hasStatus(OrderStatusCode.NEW)) {
            throw new OrderStateException(id, order.getStatus().getCode(), attempted);
        }

        ReferenceItem status = references.statusItem(target);
        order.setStatus(references.status(status));
        historyRepository.save(historyRow(order));
        outbox.append(order, eventType);

        return OrderResponse.of(order, ReferenceResponse.of(status),
                ReferenceResponse.of(order.getDeliveryType()),
                ReferenceResponse.of(order.getPaymentMethod()));
    }

    private OrderCreation place(OrderRequest request, String key) {
        Map<UUID, Integer> lines = aggregate(request.items());

        Optional<OrderResponse> replayed = replay(request.customerId(), key);
        if (replayed.isPresent()) {
            return new OrderCreation(replayed.get(), false);
        }

        ReferenceItem status = references.statusItem(OrderStatusCode.NEW);
        ReferenceItem deliveryType = references.deliveryTypeItem(request.deliveryTypeCode());
        ReferenceItem paymentMethod = references.paymentMethodItem(request.paymentMethodCode());

        UUID orderId = UUID.randomUUID();

        CompletableFuture<Map<UUID, CatalogProduct>> products =
                executor.submit(() -> catalogClient.products(lines.keySet()));
        CompletableFuture<DeliveryTarget> target =
                executor.submit(() -> deliveryTarget(request));

        CompletableFuture.allOf(products, target).exceptionally(failure -> null).join();
        Map<UUID, CatalogProduct> catalog = join(products);
        DeliveryTarget delivery = join(target);
        checkSellable(lines.keySet(), catalog);
        checkNotBlocked(delivery);

        ReserveResult reserved = inventoryClient.reserve(orderId, lines);
        if (!reserved.reserved()) {
            throw new OrderRejectedException("OUT_OF_STOCK",
                    "Out of stock: " + reserved.unavailableProductIds());
        }

        try {
            CustomerOrder order = transactionTemplate.execute(tx -> persist(
                    orderId, key, request, lines, catalog, delivery, status, deliveryType, paymentMethod));
            log.debug("Order {} placed for customer {}", orderId, request.customerId());
            return new OrderCreation(OrderResponse.of(order, ReferenceResponse.of(status),
                    ReferenceResponse.of(deliveryType), ReferenceResponse.of(paymentMethod)), true);
        } catch (DataIntegrityViolationException e) {
            inventoryClient.releaseQuietly(orderId);
            return duplicate(request.customerId(), key, e);
        } catch (RuntimeException e) {
            inventoryClient.releaseQuietly(orderId);
            throw e;
        }
    }

    private CustomerOrder persist(UUID orderId, String key, OrderRequest request,
                                  Map<UUID, Integer> lines, Map<UUID, CatalogProduct> catalog,
                                  DeliveryTarget delivery, ReferenceItem status,
                                  ReferenceItem deliveryType, ReferenceItem paymentMethod) {
        CustomerOrder order = new CustomerOrder();
        order.setId(orderId);
        order.setCustomerId(request.customerId());
        order.setCustomerEmail(delivery.customer().email());
        order.setIdempotencyKey(key);
        order.setStatus(references.status(status));
        order.setDeliveryType(references.deliveryType(deliveryType));
        order.setPaymentMethod(references.paymentMethod(paymentMethod));
        order.setAddress(snapshot(delivery.address()));
        lines.forEach((productId, quantity) -> {
            CatalogProduct product = catalog.get(productId);
            order.addItem(productId, product.name(), product.price(), quantity);
        });

        orderRepository.saveAndFlush(order);
        historyRepository.save(historyRow(order));
        return order;
    }

    private DeliveryTarget deliveryTarget(OrderRequest request) {
        try {
            return customerClient.deliveryTarget(request.customerId(), request.addressId());
        } catch (UpstreamNotFoundException e) {
            throw new OrderRejectedException("DELIVERY_TARGET_NOT_FOUND", e.getMessage());
        }
    }

    private Optional<OrderResponse> replay(UUID customerId, String key) {
        if (key == null) {
            return Optional.empty();
        }
        return transactionTemplate.execute(tx -> orderRepository
                .findByCustomerIdAndIdempotencyKey(customerId, key)
                .map(OrderResponse::of));
    }

    private OrderCreation duplicate(UUID customerId, String key, DataIntegrityViolationException e) {
        String cause = String.valueOf(e.getMostSpecificCause().getMessage());
        if (key == null || !cause.contains(IDEMPOTENCY_INDEX)) {
            throw e;
        }
        return replay(customerId, key)
                .map(order -> new OrderCreation(order, false))
                .orElseThrow(() -> new ConcurrentChangeException(
                        "Order with idempotency key " + key + " is being created by another request", e));
    }

    private static void checkSellable(Set<UUID> requested, Map<UUID, CatalogProduct> catalog) {
        List<UUID> unavailable = requested.stream()
                .filter(id -> !catalog.containsKey(id) || !catalog.get(id).active())
                .toList();
        if (!unavailable.isEmpty()) {
            throw new OrderRejectedException("PRODUCT_UNAVAILABLE",
                    "Products are unknown or withdrawn from sale: " + unavailable);
        }
    }

    private static void checkNotBlocked(DeliveryTarget delivery) {
        DeliveryTarget.Reference status = delivery.customer().status();
        if (status != null && BLOCKED.equals(status.code())) {
            throw new OrderRejectedException("CUSTOMER_BLOCKED",
                    "Customer " + delivery.customer().id() + " is blocked");
        }
    }

    private static DeliveryAddress snapshot(DeliveryTarget.Address address) {
        return new DeliveryAddress(address.city().code(), address.city().name(), address.street(),
                address.building(), address.apartment(), address.postalCode());
    }

    private static OrderStatusHistory historyRow(CustomerOrder order) {
        OrderStatusHistory row = new OrderStatusHistory();
        row.setOrder(order);
        row.setStatus(order.getStatus());
        return row;
    }

    private static Map<UUID, Integer> aggregate(List<OrderItemRequest> items) {
        Map<UUID, Integer> lines = new LinkedHashMap<>();
        for (OrderItemRequest item : items) {
            lines.merge(item.productId(), item.quantity(), Integer::sum);
        }
        return lines;
    }

    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    private CustomerOrder require(UUID id) {
        return orderRepository.findWithItemsById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Order", id));
    }

    private static void checkSortable(Sort sort) {
        sort.forEach(order -> {
            if (!SORTABLE.contains(order.getProperty())) {
                throw new IllegalArgumentException("Cannot sort by " + order.getProperty()
                        + "; sortable properties: " + String.join(", ", SORTABLE));
            }
        });
    }
}
