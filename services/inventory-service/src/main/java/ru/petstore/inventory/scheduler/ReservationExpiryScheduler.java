package ru.petstore.inventory.scheduler;

import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.petstore.inventory.service.ReservationService;

@Component
public class ReservationExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryScheduler.class);

    private final ReservationService reservationService;

    public ReservationExpiryScheduler(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @Scheduled(
            fixedDelayString = "${petstore.inventory.expiry-scan-interval:PT1M}",
            initialDelayString = "${petstore.inventory.expiry-scan-interval:PT1M}")
    @SchedulerLock(name = "inventory-reservation-expiry", lockAtLeastFor = "PT5S", lockAtMostFor = "PT5M")
    public void releaseExpiredReservations() {
        List<UUID> overdue;
        try {
            overdue = reservationService.expiredReservationIds();
        } catch (RuntimeException e) {
            log.error("Sweep of expired reservations failed, retrying on the next pass", e);
            return;
        }

        int released = 0;
        for (UUID reservationId : overdue) {
            try {
                if (reservationService.releaseExpired(reservationId)) {
                    released++;
                }
            } catch (RuntimeException e) {
                log.error("Failed to release expired reservation {}", reservationId, e);
            }
        }
        if (released > 0) {
            log.info("Released {} expired reservation(s)", released);
        }
    }
}
