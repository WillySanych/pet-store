package ru.petstore.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.petstore.inventory.domain.ReservationStatusCode;
import ru.petstore.inventory.repository.ReservationRepository;
import ru.petstore.inventory.service.ReservationService;
import ru.petstore.inventory.service.ReservationStateException;
import ru.petstore.inventory.service.ReserveLine;
import ru.petstore.inventory.service.StockService;
import ru.petstore.inventory.web.dto.StockRequest;
import ru.petstore.inventory.web.dto.StockResponse;

/**
 * The reservation lifecycle against a real database: what moves stock, what only holds it, and which
 * repeats change nothing.
 */
@SpringBootTest(properties = {
        "spring.liquibase.contexts=test",
        "grpc.server.port=0",
        "spring.kafka.listener.auto-startup=false"
})
class ReservationFlowTest extends AbstractPostgresTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private StockService stockService;

    @Autowired
    private ReservationRepository reservationRepository;

    private UUID productWithStock(int quantity) {
        UUID productId = UUID.randomUUID();
        stockService.set(productId, new StockRequest("MSK", quantity));
        return productId;
    }

    private StockResponse stockOf(UUID productId) {
        return stockService.get(productId);
    }

    private static List<ReserveLine> lines(UUID productId, int quantity) {
        return List.of(new ReserveLine(productId, quantity));
    }

    @Test
    @DisplayName("Резерв удерживает остаток, но не списывает его")
    void reserveHoldsStockWithoutTakingItOffTheShelf() {
        UUID product = productWithStock(10);

        var outcome = reservationService.reserve(UUID.randomUUID(), lines(product, 3));

        assertThat(outcome.reserved()).isTrue();
        assertThat(outcome.unavailableProductIds()).isEmpty();
        assertThat(stockOf(product).quantity()).isEqualTo(10);
        assertThat(stockOf(product).reserved()).isEqualTo(3);
        assertThat(stockOf(product).available()).isEqualTo(7);
    }

    @Test
    @DisplayName("Повторный Reserve по тому же заказу не удерживает остаток второй раз")
    void repeatedReserveHoldsNothingTwice() {
        UUID product = productWithStock(10);
        UUID orderId = UUID.randomUUID();

        reservationService.reserve(orderId, lines(product, 3));
        var repeat = reservationService.reserve(orderId, lines(product, 3));

        assertThat(repeat.reserved()).isTrue();
        assertThat(stockOf(product).reserved()).isEqualTo(3);
        assertThat(reservationRepository.findByOrderId(orderId)).isPresent();
    }

    @Test
    @DisplayName("Нехватки остатка достаточно, чтобы не удержать ничего")
    void reserveIsAllOrNothing() {
        UUID plenty = productWithStock(10);
        UUID scarce = productWithStock(1);

        var outcome = reservationService.reserve(UUID.randomUUID(),
                List.of(new ReserveLine(plenty, 2), new ReserveLine(scarce, 5)));

        assertThat(outcome.reserved()).isFalse();
        assertThat(outcome.unavailableProductIds()).containsExactly(scarce);
        assertThat(stockOf(plenty).reserved()).isZero();
        assertThat(stockOf(scarce).reserved()).isZero();
    }

    @Test
    @DisplayName("Один товар двумя строками — это сумма, а не две попытки")
    void duplicateLinesAreSummed() {
        UUID product = productWithStock(10);

        var outcome = reservationService.reserve(UUID.randomUUID(),
                List.of(new ReserveLine(product, 2), new ReserveLine(product, 3)));

        assertThat(outcome.reserved()).isTrue();
        assertThat(stockOf(product).reserved()).isEqualTo(5);
    }

    @Test
    @DisplayName("Одним товаром двумя строками можно превысить остаток — и это отказ")
    void duplicateLinesTogetherCanExceedTheStock() {
        UUID product = productWithStock(4);

        var outcome = reservationService.reserve(UUID.randomUUID(),
                List.of(new ReserveLine(product, 3), new ReserveLine(product, 3)));

        assertThat(outcome.reserved()).isFalse();
        assertThat(outcome.unavailableProductIds()).containsExactly(product);
        assertThat(stockOf(product).reserved()).isZero();
    }

    @Test
    @DisplayName("Товар без строки остатка недоступен, а не доступен в неограниченном количестве")
    void productWithoutStockRowIsUnavailable() {
        UUID unknown = UUID.randomUUID();

        var outcome = reservationService.reserve(UUID.randomUUID(), lines(unknown, 1));

        assertThat(outcome.reserved()).isFalse();
        assertThat(outcome.unavailableProductIds()).containsExactly(unknown);
    }

    @Test
    @DisplayName("Подтверждение списывает остаток и закрывает резерв")
    void commitWritesStockOff() {
        UUID product = productWithStock(10);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, lines(product, 3));

        assertThat(reservationService.commit(orderId)).isTrue();

        assertThat(stockOf(product).quantity()).isEqualTo(7);
        assertThat(stockOf(product).reserved()).isZero();
        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow()
                .hasStatus(ReservationStatusCode.COMMITTED)).isTrue();
    }

    @Test
    @DisplayName("Повторная доставка ORDER_CONFIRMED не списывает остаток дважды")
    void repeatedCommitWritesStockOffOnce() {
        UUID product = productWithStock(10);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, lines(product, 3));
        reservationService.commit(orderId);

        assertThat(reservationService.commit(orderId)).isTrue();

        assertThat(stockOf(product).quantity()).isEqualTo(7);
    }

    @Test
    @DisplayName("Освобождение возвращает остаток в продажу")
    void releaseGivesTheHoldBack() {
        UUID product = productWithStock(10);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, lines(product, 3));

        assertThat(reservationService.release(orderId)).isTrue();

        assertThat(stockOf(product).quantity()).isEqualTo(10);
        assertThat(stockOf(product).reserved()).isZero();
        assertThat(reservationRepository.findByOrderId(orderId).orElseThrow()
                .hasStatus(ReservationStatusCode.RELEASED)).isTrue();
    }

    @Test
    @DisplayName("Повторное освобождение ничего не меняет")
    void repeatedReleaseIsANoOp() {
        UUID product = productWithStock(10);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, lines(product, 3));
        reservationService.release(orderId);

        assertThat(reservationService.release(orderId)).isTrue();

        assertThat(stockOf(product).reserved()).isZero();
        assertThat(stockOf(product).quantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("Компенсация по заказу без резерва — успех, а не ошибка")
    void releaseWithoutReservationSucceeds() {
        assertThat(reservationService.release(UUID.randomUUID())).isTrue();
    }

    @Test
    @DisplayName("Списанный резерв освободить нельзя — остаток уже ушёл покупателю")
    void releaseAfterCommitIsRefused() {
        UUID product = productWithStock(10);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, lines(product, 3));
        reservationService.commit(orderId);

        assertThat(reservationService.release(orderId)).isFalse();

        assertThat(stockOf(product).quantity()).isEqualTo(7);
        assertThat(stockOf(product).reserved()).isZero();
    }

    @Test
    @DisplayName("Подтверждение после освобождения не списывает остаток задним числом")
    void commitAfterReleaseIsRefused() {
        UUID product = productWithStock(10);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, lines(product, 3));
        reservationService.release(orderId);

        assertThat(reservationService.commit(orderId)).isFalse();

        assertThat(stockOf(product).quantity()).isEqualTo(10);
        assertThat(stockOf(product).reserved()).isZero();
    }

    @Test
    @DisplayName("Подтверждение неизвестного заказа не создаёт резерв и не трогает остаток")
    void commitWithoutReservationChangesNothing() {
        assertThat(reservationService.commit(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("Повтор Reserve по освобождённому заказу — отказ по состоянию, а не «товар кончился»")
    void reserveAfterReleaseIsRejectedByState() {
        UUID product = productWithStock(10);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, lines(product, 3));
        reservationService.release(orderId);

        assertThatThrownBy(() -> reservationService.reserve(orderId, lines(product, 3)))
                .isInstanceOf(ReservationStateException.class)
                .hasMessageContaining("RELEASED");

        assertThat(stockOf(product).reserved()).isZero();
    }

    @Test
    @DisplayName("Пустой и отрицательный запрос отбиваются до обращения к остаткам")
    void malformedRequestsAreRejected() {
        UUID product = productWithStock(10);

        assertThatThrownBy(() -> reservationService.reserve(UUID.randomUUID(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reservationService.reserve(UUID.randomUUID(), lines(product, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reservationService.reserve(UUID.randomUUID(), lines(product, -1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reservationService.reserve(null, lines(product, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Удержанный остаток нельзя занизить установкой количества")
    void stockCannotBeSetBelowWhatIsHeld() {
        UUID product = productWithStock(10);
        reservationService.reserve(UUID.randomUUID(), lines(product, 6));

        assertThatThrownBy(() -> stockService.set(product, new StockRequest("MSK", 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("held by reservations");

        assertThat(stockOf(product).quantity()).isEqualTo(10);
    }
}
