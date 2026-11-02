package com.bizsim.db;

import com.bizsim.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check of the first-run path: a database that does not exist must be created by the
 * initializer, after which the same initializer must be a no-op.
 */
class DatabaseBootstrapCreateTest extends PostgresTestBase {

    @Test
    void creates_a_missing_database_and_then_does_nothing() throws Exception {
        String database = "bizsim_boot_" + System.nanoTime();
        String url = urlFor(database);

        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", url);
        environment.setProperty("spring.datasource.username", "postgres");
        environment.setProperty("spring.datasource.password", "postgres");

        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(environment);

        DatabaseBootstrap bootstrap = new DatabaseBootstrap();
        bootstrap.initialize(context);

        try (Connection connection = DriverManager.getConnection(url, "postgres", "postgres")) {
            assertThat(connection.isValid(2)).isTrue();
        }

        // Second call must not fail and must not try to create it again.
        bootstrap.initialize(context);
        context.close();
    }

    @Test
    void leaves_the_maintenance_database_alone() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", urlFor("postgres"));
        environment.setProperty("spring.datasource.username", "postgres");
        environment.setProperty("spring.datasource.password", "postgres");

        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(environment);

        new DatabaseBootstrap().initialize(context);
        context.close();
    }

    @Test
    void can_be_switched_off() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", urlFor("bizsim_disabled_" + System.nanoTime()));
        environment.setProperty("bizsim.db.create-if-missing", "false");
        environment.setProperty("spring.datasource.username", "postgres");
        environment.setProperty("spring.datasource.password", "postgres");

        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(environment);

        new DatabaseBootstrap().initialize(context);
        context.close();
    }
}
