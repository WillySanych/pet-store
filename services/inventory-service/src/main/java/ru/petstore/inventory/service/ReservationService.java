package ru.petstore.inventory.service;

import static ru.petstore.inventory.domain.ReservationStatusCode.ACTIVE;
import static ru.petstore.inventory.domain.ReservationStatusCode.COMMITTED;
import static ru.petstore.inventory.domain.ReservationStatusCode.EXPIRED;
import static ru.petstore.inventory.domain.ReservationStatusCode.RELEASED;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.petstore.common.metrics.ServiceMetrics;
import ru.petstore.common.reference.ReferenceDataService;
import ru.petstore.inventory.config.InventoryProperties;
import ru.petstore.inventory.domain.Reservation;
import ru.petstore.inventory.domain.ReservationItem;
import ru.petstore.inventory.domain.ReservationStatus;
import ru.petstore.inventory.domain.ReservationStatusCode;
import ru.petstore.inventory.repository.ReservationRepository;
import ru.petstore.inventory.repository.ReservationStatusRepository;
import ru.petstore.inventory.repository.StockItemRepository;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationRepository reservationRepository;
    private final StockItemRepository stockItemRepository;
    private final ReservationStatusRepository reservationStatusRepository;
    private final ReferenceDataService referenceDataService;
    private final ServiceMetrics serviceMetrics;
    private final InventoryProperties inventoryProperties;
    private final TransactionTemplate transactionTemplate;

    public ReservationService(ReservationRepository reservationRepository,
                              StockItemRepository stockItemRepository,
                              ReservationStatusRepository reservationStatusRepository,
                              ReferenceDataService referenceDataService,
                              ServiceMetrics serviceMetrics,
                              InventoryProperties inventoryProperties,
                              PlatformTransactionManager transactionManager) {
        this.reservationRepository = reservationRepository;
        this.stockItemRepository = stockItemRepository;
        this.reservationStatusRepository = reservationStatusRepository;
        this.referenceDataService = referenceDataService;
        this.serviceMetrics = serviceMetrics;
        this.inventoryProperties = inventoryProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ReserveOutcome reserve(UUID orderId, List<ReserveLine> lines) {
        requireOrderId(orderId);
        Map<UUID, Integer> requested = aggregate(lines);
        return Objects.requireNonNull(transactionTemplate.execute(
                transaction -> reserveInTransaction(orderId, requested, transaction)));
    }

    private ReserveOutcome reserveInTransaction(UUID orderId, Map<UUID, Integer> requested,
                                                TransactionStatus transaction) {
        Optional<Reservation> existing = reservationRepository.findByOrderIdForUpdate(orderId);
        if (existing.isPresent()) {
            return outcomeOfExisting(existing.get());
        }

        List<UUID> unavailable = new ArrayList<>();
        for (Map.Entry<UUID, Integer> line : sortedLines(requested)) {
            if (stockItemRepository.reserveIfAvailable(line.getKey(), line.getValue()) == 0) {
                unavailable.add(line.getKey());
            }
        }
        if (!unavailable.isEmpty()) {
            transaction.setRollbackOnly();
            log.debug("Reserve for order {} refused, short of {}", orderId, unavailable);
            return ReserveOutcome.refused(unavailable);
        }

        Reservation reservation = new Reservation();
        reservation.setOrderId(orderId);
        reservation.setStatus(statusRef(ACTIVE));
        reservation.setExpiresAt(Instant.now().plus(inventoryProperties.getReservationTtl()));
        sortedLines(requested).forEach(line -> reservation.addItem(line.getKey(), line.getValue()));

        try {
            reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            throw new ConcurrentReservationException(orderId, e);
        }

        log.debug("Order {} holds {} product(s) until {}", orderId, requested.size(), reservation.getExpiresAt());
        return ReserveOutcome.held();
    }

    @Transactional
    public boolean release(UUID orderId) {
        requireOrderId(orderId);
        Reservation reservation = reservationRepository.findByOrderIdForUpdate(orderId).orElse(null);
        if (reservation == null) {
            log.debug("Release for order {}: no reservation, nothing to do", orderId);
            return true;
        }
        if (reservation.hasStatus(COMMITTED)) {
            serviceMetrics.recordError("release_after_commit");
            log.error("Release for order {} refused: the stock is already written off", orderId);
            return false;
        }
        if (!reservation.hasStatus(ACTIVE)) {
            return true;
        }

        giveBack(reservation, RELEASED);
        log.debug("Order {} released its hold", orderId);
        return true;
    }

    @Transactional
    public boolean commit(UUID orderId) {
        requireOrderId(orderId);
        Reservation reservation = reservationRepository.findByOrderIdForUpdate(orderId).orElse(null);
        if (reservation == null) {
            serviceMetrics.recordError("confirm_without_reservation");
            log.error("ORDER_CONFIRMED for order {} found no reservation", orderId);
            return false;
        }
        if (reservation.hasStatus(COMMITTED)) {
            log.debug("ORDER_CONFIRMED for order {} already applied", orderId);
            return true;
        }
        if (!reservation.hasStatus(ACTIVE)) {
            serviceMetrics.recordError("confirm_after_release");
            log.error("ORDER_CONFIRMED for order {} arrived after the hold was given back ({})",
                    orderId, reservation.getStatus().getCode());
            return false;
        }

        for (ReservationItem item : sortedItems(reservation)) {
            if (stockItemRepository.commitIfReserved(item.getProductId(), item.getQuantity()) == 0) {
                serviceMetrics.recordError("stock_missing_on_confirm");
                throw inconsistentStock("commit", reservation, item);
            }
        }
        reservation.setStatus(statusRef(COMMITTED));
        log.debug("Order {} written off {} product(s)", orderId, reservation.getItems().size());
        return true;
    }

    @Transactional(readOnly = true)
    public List<UUID> expiredReservationIds() {
        return reservationRepository.findOverdueIds(
                ACTIVE.code(), Instant.now(), Limit.of(inventoryProperties.getExpiryBatchSize()));
    }

    @Transactional
    public boolean releaseExpired(UUID reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId).orElse(null);
        if (reservation == null || !reservation.hasStatus(ACTIVE)) {
            return false;
        }
        giveBack(reservation, EXPIRED);
        return true;
    }

    private void giveBack(Reservation reservation, ReservationStatusCode newStatus) {
        for (ReservationItem item : sortedItems(reservation)) {
            if (stockItemRepository.releaseIfReserved(item.getProductId(), item.getQuantity()) == 0) {
                serviceMetrics.recordError("stock_missing_on_release");
                throw inconsistentStock("release", reservation, item);
            }
        }
        reservation.setStatus(statusRef(newStatus));
    }

    private ReserveOutcome outcomeOfExisting(Reservation reservation) {
        if (reservation.hasStatus(ACTIVE) || reservation.hasStatus(COMMITTED)) {
            return ReserveOutcome.held();
        }
        throw new ReservationStateException(
                reservation.getOrderId(), reservation.getStatus().getCode(), "reserved again");
    }

    private static Map<UUID, Integer> aggregate(List<ReserveLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Reservation must have at least one item");
        }
        Map<UUID, Integer> requested = new LinkedHashMap<>();
        for (ReserveLine line : lines) {
            if (line.productId() == null) {
                throw new IllegalArgumentException("Reservation item must have a product id");
            }
            if (line.quantity() <= 0) {
                throw new IllegalArgumentException(
                        "Quantity for product " + line.productId() + " must be positive");
            }
            try {
                requested.merge(line.productId(), line.quantity(), Math::addExact);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(
                        "Total quantity for product " + line.productId() + " is too large", e);
            }
        }
        return requested;
    }

    private static List<Map.Entry<UUID, Integer>> sortedLines(Map<UUID, Integer> requested) {
        return requested.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    }

    private static List<ReservationItem> sortedItems(Reservation reservation) {
        return reservation.getItems().stream()
                .sorted((left, right) -> left.getProductId().compareTo(right.getProductId()))
                .toList();
    }

    private static IllegalStateException inconsistentStock(String operation, Reservation reservation,
                                                           ReservationItem item) {
        return new IllegalStateException("Cannot " + operation + " " + item.getQuantity()
                + " reserved unit(s) of product " + item.getProductId() + " for order "
                + reservation.getOrderId() + ": stock row is missing or inconsistent");
    }

    private ReservationStatus statusRef(ReservationStatusCode code) {
        return reservationStatusRepository.getReferenceById(
                referenceDataService.getRequired(ReferenceType.RESERVATION_STATUS, code.code()).id());
    }

    private static void requireOrderId(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("Order id is required");
        }
    }
}
