package ru.petstore.inventory.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import ru.petstore.inventory.service.ReservationService;

@ExtendWith(MockitoExtension.class)
class ReservationExpirySchedulerTest {

    @Mock
    private ReservationService reservationService;

    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    private void sweep() {
        new ReservationExpiryScheduler(reservationService).releaseExpiredReservations();
    }

    @Test
    @DisplayName("Проход освобождает каждый просроченный резерв по отдельности")
    void sweepReleasesEveryOverdueReservation() {
        when(reservationService.expiredReservationIds()).thenReturn(List.of(first, second));

        sweep();

        verify(reservationService).releaseExpired(first);
        verify(reservationService).releaseExpired(second);
    }

    @Test
    @DisplayName("Проигранная гонка по одному резерву не отменяет остальные")
    void lostRaceOnOneReservationDoesNotStopTheRest() {
        when(reservationService.expiredReservationIds()).thenReturn(List.of(first, second));
        when(reservationService.releaseExpired(first))
                .thenThrow(new ObjectOptimisticLockingFailureException("stock_item", first));

        assertThatCode(this::sweep).doesNotThrowAnyException();

        verify(reservationService).releaseExpired(second);
    }

    @Test
    @DisplayName("Упавший проход не убивает расписание")
    void failedSweepDoesNotBreakTheSchedule() {
        when(reservationService.expiredReservationIds())
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(this::sweep).doesNotThrowAnyException();
    }
}
