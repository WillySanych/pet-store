package ru.petstore.order.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "petstore.order.outbox-poll-interval=PT1H",
        "spring.kafka.admin.auto-create=false"
})
class DefaultLiquibaseContextTest extends AbstractPostgresTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Таблица блокировок планировщика заводится миграцией сервиса")
    void schedulerLockTableIsCreatedByTheService() {
        Long lockTable = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'orders' AND table_name = 'shedlock'""", Long.class);

        assertThat(lockTable).isOne();
    }

    @Test
    @DisplayName("Справочники засеяны: статусы, доставка, оплата")
    void referenceDataIsSeeded() {
        assertThat(count("order_status")).isEqualTo(3);
        assertThat(count("delivery_type")).isEqualTo(3);
        assertThat(count("payment_method")).isEqualTo(3);
    }

    @Test
    @DisplayName("Частичный индекс outbox заводится: по нему ходит публикатор")
    void outboxIndexIsPartial() {
        String definition = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_outbox_unpublished'", String.class);

        assertThat(definition).contains("WHERE (published_at IS NULL)");
    }

    private Long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM orders." + table, Long.class);
    }
}
