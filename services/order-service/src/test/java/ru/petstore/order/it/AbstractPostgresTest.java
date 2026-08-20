package ru.petstore.order.it;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A throwaway PostgreSQL for the tests that need a real database, with the schema created by
 * Liquibase. One container for the whole module: starting it per class costs more than every test.
 */
abstract class AbstractPostgresTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.14")
            .withInitScript("db/testcontainers-init.sql");

    static {
        POSTGRES.start();
    }
}
