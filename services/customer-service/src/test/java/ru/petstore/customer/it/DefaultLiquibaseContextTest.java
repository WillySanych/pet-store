package ru.petstore.customer.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class DefaultLiquibaseContextTest extends AbstractPostgresTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("По умолчанию демо-клиенты не заводятся: контекст demo надо запросить явно")
    void demoCustomersAreNotSeededByDefault() {
        Long demoApplied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customer.databasechangelog WHERE id = '003-1-demo-customers'",
                Long.class);
        Long schemaApplied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customer.databasechangelog WHERE id = '001-1-city'",
                Long.class);

        assertThat(demoApplied).isZero();
        assertThat(schemaApplied).isOne();
    }
}
