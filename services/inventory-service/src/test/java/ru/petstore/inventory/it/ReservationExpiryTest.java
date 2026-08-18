package ru.petstore.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.petstore.inventory.domain.ReservationStatusCode;
import ru.petstore.inventory.repository.ReservationRepository;
import ru.petstore.inventory.service.ReservationService;
import ru.petstore.inventory.service.ReserveLine;
import ru.petstore.inventory.service.StockService;
import ru.petstore.inventory.web.dto.StockRequest;

/**
 * The sweeper behind the two event-driven paths. Holds get a zero lifetime here, so a reservation is
 * overdue the moment it is taken.
 */
@SpringBootTest(properties = {
        "spring.liquibase.contexts=test",
        "grpc.server.port=0",
        "spring.kafka.listener.auto-startup=false",
        "petstore.inventory.reservation-ttl=PT0S",
        "petstore.inventory.expiry-batch-size=1"
})
class ReservationExpiryTest extends AbstractPostgresTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private StockService stockService;

    @Autowired
    private ReservationRepository reservationRepository;

    private UUID reserve(UUID product, int quantity) {
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(new ReserveLine(product, quantity)));
        return orderId;
    }

    /** One pass of the sweeper, the way {@code ReservationExpiryScheduler} runs it. */
    private int sweep() {
        int released = 0;
        for (UUID reservationId : reservationService.expiredReservationIds()) {
            if (reservationService.releaseExpired(reservationId)) {
                released++;
            }
        }
        return released;
    }

    @Test
    @DisplayName("Просроченный резерв возвращает остаток в продажу")
    void expiredReservationGivesTheStockBack() {
        UUID product = UUID.randomUUID();
        stockService.set(product, new StockRequest("MSK", 10));
        UUID orderId = reserve(product, 4);
        assertThat(stockService.get(product).available()).isEqualTo(6);

        assertThat(sweep()).isPositive();

        assertThat(stockService.get(product).reserved()).isZero();
        assertThat(stockService.get(product).quantity()).isEqualTo(10);
        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow()
                .hasStatus(ReservationStatusCode.EXPIRED)).isTrue();
    }

    @Test
    @DisplayName("Списанный резерв уборщик не трогает")
    void committedReservationIsLeftAlone() {
        UUID product = UUID.randomUUID();
        stockService.set(product, new StockRequest("SPB", 10));
        UUID orderId = reserve(product, 4);
        reservationService.commit(orderId);

        sweep();

        assertThat(stockService.get(product).quantity()).isEqualTo(6);
        assertThat(stockService.get(product).reserved()).isZero();
        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow()
                .hasStatus(ReservationStatusCode.COMMITTED)).isTrue();
    }

    @Test
    @DisplayName("Проход ограничен размером батча — остальное достаётся следующему")
    void sweepIsBoundedByTheBatchSize() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        stockService.set(first, new StockRequest("EKB", 10));
        stockService.set(second, new StockRequest("EKB", 10));
        reserve(first, 4);
        reserve(second, 4);

        assertThat(reservationService.expiredReservationIds()).hasSize(1);
        assertThat(sweep()).isEqualTo(1);
        assertThat(sweep()).isEqualTo(1);

        assertThat(stockService.get(first).reserved()).isZero();
        assertThat(stockService.get(second).reserved()).isZero();
    }
}
