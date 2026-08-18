package ru.petstore.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.petstore.inventory.scheduler.ReservationExpiryScheduler;

/**
 * That the sweep really runs under a lock, in the schema of this service. Unqualified, the table
 * name would silently resolve to {@code public} and every replica would sweep on its own.
 */
@SpringBootTest(properties = {
        "spring.liquibase.contexts=test",
        "grpc.server.port=0",
        "spring.kafka.listener.auto-startup=false"
})
class SchedulerLockTest extends AbstractPostgresTest {

    private static final String LOCK_NAME = "inventory-reservation-expiry";

    @Autowired
    private ReservationExpiryScheduler reservationExpiryScheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Проход уборщика берёт блокировку в таблице своей схемы")
    void sweepTakesTheLockInTheServiceSchema() {
        reservationExpiryScheduler.releaseExpiredReservations();

        Long locks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory.shedlock WHERE name = ?", Long.class, LOCK_NAME);
        assertThat(locks).isOne();

        // lock_until beyond locked_at is what stops a second replica from starting the same sweep.
        Boolean stillHeld = jdbcTemplate.queryForObject(
                "SELECT lock_until > locked_at FROM inventory.shedlock WHERE name = ?",
                Boolean.class, LOCK_NAME);
        assertThat(stillHeld).isTrue();
    }
}
