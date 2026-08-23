package ru.petstore.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "grpc.server.port=0",
        "spring.kafka.listener.auto-startup=false"
})
class DefaultLiquibaseContextTest extends AbstractPostgresTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("По умолчанию демо-остатки не заводятся: контекст demo надо запросить явно")
    void demoStockIsNotSeededByDefault() {
        Long demoApplied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory.databasechangelog WHERE id = '004-1-demo-stock'",
                Long.class);
        Long schemaApplied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory.databasechangelog WHERE id = '001-3-stock-item'",
                Long.class);

        assertThat(demoApplied).isZero();
        assertThat(schemaApplied).isOne();
    }
}
