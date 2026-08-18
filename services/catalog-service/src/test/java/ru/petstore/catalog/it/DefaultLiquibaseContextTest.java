package ru.petstore.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "grpc.server.port=0")
class DefaultLiquibaseContextTest extends AbstractPostgresTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("По умолчанию демо-товары не заводятся: контекст demo надо запросить явно")
    void demoGoodsAreNotSeededByDefault() {
        Long demoApplied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM catalog.databasechangelog WHERE id = '003-1-demo-products'",
                Long.class);
        Long schemaApplied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM catalog.databasechangelog WHERE id = '001-1-category'",
                Long.class);

        assertThat(demoApplied).isZero();
        assertThat(schemaApplied).isOne();
    }
}
