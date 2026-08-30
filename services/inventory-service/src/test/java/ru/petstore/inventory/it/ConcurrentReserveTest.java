package ru.petstore.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;

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
import ru.petstore.inventory.domain.ReservationStatusCode;
import ru.petstore.inventory.repository.ReservationRepository;
import ru.petstore.inventory.service.ReservationService;
import ru.petstore.inventory.service.ReserveLine;
import ru.petstore.inventory.service.StockService;
import ru.petstore.inventory.web.dto.StockRequest;

/**
 * The conditional PostgreSQL update serializes changes to a hot stock row. Two orders may both
 * attempt the request at the same time, but one gets a normal stock refusal rather than an
 * optimistic-lock exception, and the shelf is never oversold.
 */
@SpringBootTest(properties = {
        "spring.liquibase.contexts=test",
        "grpc.server.port=0",
        "spring.kafka.listener.auto-startup=false"
})
class ConcurrentReserveTest extends AbstractPostgresTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private StockService stockService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Test
    @DisplayName("Два параллельных резерва по одному товару не перепродают остаток")
    void twoConcurrentReservationsCannotOversell() throws Exception {
        UUID product = UUID.randomUUID();
        stockService.set(product, new StockRequest("MSK", 10));

        // Six each: separately both fit into ten, together they do not.
        var barrier = new CyclicBarrier(2);
        Callable<Boolean> reserve = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return reservationService.reserve(UUID.randomUUID(), List.of(new ReserveLine(product, 6)))
                    .reserved();
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = pool.invokeAll(
                    List.of(reserve, reserve), 20, TimeUnit.SECONDS);

            int held = 0;
            for (Future<Boolean> result : results) {
                if (result.get(20, TimeUnit.SECONDS)) {
                    held++;
                }
            }

            assertThat(held).isOne();
            assertThat(stockService.get(product).reserved()).isEqualTo(6);
            assertThat(stockService.get(product).available()).isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Конкурентные многотоварные резервы не оставляют частичное удержание")
    void concurrentMultiProductReservationsStayAllOrNothing() throws Exception {
        UUID firstProduct = UUID.randomUUID();
        UUID secondProduct = UUID.randomUUID();
        stockService.set(firstProduct, new StockRequest("MSK", 10));
        stockService.set(secondProduct, new StockRequest("MSK", 10));

        var barrier = new CyclicBarrier(2);
        Callable<Boolean> forward = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return reservationService.reserve(UUID.randomUUID(), List.of(
                    new ReserveLine(firstProduct, 6), new ReserveLine(secondProduct, 6))).reserved();
        };
        Callable<Boolean> reverse = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return reservationService.reserve(UUID.randomUUID(), List.of(
                    new ReserveLine(secondProduct, 6), new ReserveLine(firstProduct, 6))).reserved();
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = pool.invokeAll(
                    List.of(forward, reverse), 20, TimeUnit.SECONDS);

            assertThat(results).allSatisfy(result -> assertThat(result.isCancelled()).isFalse());
            assertThat(results.stream().filter(result -> get(result)).count()).isOne();
            assertThat(stockService.get(firstProduct).reserved()).isEqualTo(6);
            assertThat(stockService.get(secondProduct).reserved()).isEqualTo(6);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Одновременные commit и release применяют только один переход резерва")
    void concurrentCommitAndReleaseApplyOneTransition() throws Exception {
        UUID product = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        stockService.set(product, new StockRequest("MSK", 10));
        reservationService.reserve(orderId, List.of(new ReserveLine(product, 6)));

        var barrier = new CyclicBarrier(2);
        Callable<Boolean> commit = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return reservationService.commit(orderId);
        };
        Callable<Boolean> release = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return reservationService.release(orderId);
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = pool.invokeAll(
                    List.of(commit, release), 20, TimeUnit.SECONDS);
            boolean committed = get(results.get(0));
            boolean released = get(results.get(1));

            assertThat(committed).isNotEqualTo(released);
            assertThat(stockService.get(product).reserved()).isZero();
            var reservation = reservationRepository.findByOrderId(orderId).orElseThrow();
            if (committed) {
                assertThat(reservation.hasStatus(ReservationStatusCode.COMMITTED)).isTrue();
                assertThat(stockService.get(product).quantity()).isEqualTo(4);
            } else {
                assertThat(reservation.hasStatus(ReservationStatusCode.RELEASED)).isTrue();
                assertThat(stockService.get(product).quantity()).isEqualTo(10);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean get(Future<Boolean> result) {
        try {
            return result.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("Concurrent inventory operation failed", e);
        }
    }
}
