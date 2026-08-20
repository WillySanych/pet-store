package ru.petstore.order.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.petstore.order.outbox.OutboxPublisher;

@SpringBootTest(properties = {
        "petstore.order.outbox-poll-interval=PT1H",
        "spring.kafka.admin.auto-create=false"
})
class SchedulerLockTest extends AbstractPostgresTest {

    private static final String LOCK_NAME = "order-outbox-publisher";

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Проход публикатора берёт блокировку в таблице своей схемы")
    void publishingTakesTheLockInTheServiceSchema() {
        outboxPublisher.publishPending();

        Long locks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders.shedlock WHERE name = ?", Long.class, LOCK_NAME);
        assertThat(locks).isOne();

        // lock_until beyond locked_at is what stops a second replica from shipping the same messages.
        Boolean stillHeld = jdbcTemplate.queryForObject(
                "SELECT lock_until > locked_at FROM orders.shedlock WHERE name = ?",
                Boolean.class, LOCK_NAME);
        assertThat(stillHeld).isTrue();
    }
}
