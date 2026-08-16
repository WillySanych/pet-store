package ru.petstore.catalog.it;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A throwaway PostgreSQL for the tests that need a real database, with the schema created by
 * Liquibase as in a running installation.
 */
abstract class AbstractPostgresTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.14")
            .withInitScript("db/testcontainers-init.sql");

    static {
        POSTGRES.start();
    }
}
