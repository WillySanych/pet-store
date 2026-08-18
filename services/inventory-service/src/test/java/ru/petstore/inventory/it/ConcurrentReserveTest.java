package ru.petstore.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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
import ru.petstore.inventory.service.ReservationService;
import ru.petstore.inventory.service.ReserveLine;
import ru.petstore.inventory.service.StockService;
import ru.petstore.inventory.web.dto.StockRequest;

/**
 * The reason {@code stock_item} carries a {@code @Version}: two orders reading the same free amount
 * and both holding it would oversell the shelf. Which way the loser loses is timing — a version
 * conflict or a plain refusal — so the test pins what holds either way: six units, never twelve.
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
            List<Future<Boolean>> results = pool.invokeAll(List.of(reserve, reserve));

            int held = 0;
            List<Throwable> lost = new ArrayList<>();
            for (Future<Boolean> result : results) {
                try {
                    if (result.get(20, TimeUnit.SECONDS)) {
                        held++;
                    }
                } catch (Exception e) {
                    lost.add(e);
                }
            }

            assertThat(held).isOne();
            assertThat(lost).allSatisfy(failure ->
                    assertThat(failure).hasStackTraceContaining("Optimistic"));
            assertThat(stockService.get(product).reserved()).isEqualTo(6);
            assertThat(stockService.get(product).available()).isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
    }
}
