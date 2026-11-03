package com.bizsim.db;

import com.bizsim.support.PostgresTestBase;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two properties every deploy depends on, proved rather than assumed:
 *
 * <ol>
 *   <li>a second start applies nothing (an existing database is not touched);</li>
 *   <li>a migration that fails half-way rolls back completely in PostgreSQL, so a failed deploy
 *       leaves the previous schema intact instead of a half-built one.</li>
 * </ol>
 */
class MigrationSafetyTest extends PostgresTestBase {

    private static final String USER = "postgres";

    @Test
    void a_second_start_applies_nothing() {
        String url = freshDatabase();

        Flyway flyway = Flyway.configure().dataSource(url, USER, USER)
                .locations("classpath:db/migration").load();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(15);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.info().applied()).hasSize(15);
        flyway.validate();
    }

    @Test
    void a_broken_migration_leaves_no_partial_schema_and_no_failed_row() throws Exception {
        String url = freshDatabase();
        Flyway.configure().dataSource(url, USER, USER)
                .locations("classpath:db/migration").load().migrate();

        Flyway withBroken = Flyway.configure().dataSource(url, USER, USER)
                .locations("classpath:db/migration", "classpath:db/broken").load();

        assertThatThrownBy(withBroken::migrate).isInstanceOf(FlywayException.class);

        try (Connection connection = DriverManager.getConnection(url, USER, USER)) {
            // The table created by the first statement of V99 must be gone.
            assertThat(exists(connection,
                    "select to_regclass('public.migration_probe') is not null")).isFalse();
            // PostgreSQL rolls the whole migration back, so no failed entry is left behind.
            assertThat(exists(connection,
                    "select exists (select 1 from flyway_schema_history where not success)")).isFalse();
            assertThat(exists(connection,
                    "select exists (select 1 from flyway_schema_history where version = '99')")).isFalse();
            // The application's own schema is untouched and still valid.
            assertThat(exists(connection,
                    "select to_regclass('public.round_statement') is not null")).isTrue();
        }

        Flyway.configure().dataSource(url, USER, USER)
                .locations("classpath:db/migration").load().validate();
    }

    /**
     * The exact scenario that made a previous life miserable: a file that creates a table, writes a
     * row, alters another table, indexes it, and only then hits a syntax error. Afterwards the
     * database must be byte-for-byte the same shape as before the attempt.
     */
    @Test
    void a_multi_statement_migration_that_fails_mid_file_changes_nothing() throws Exception {
        String url = freshDatabase();
        Flyway.configure().dataSource(url, USER, USER)
                .locations("classpath:db/migration").load().migrate();
        List<String> before = fingerprint(url);

        Flyway halfApplied = Flyway.configure().dataSource(url, USER, USER)
                .locations("classpath:db/migration", "classpath:db/halfapplied").load();

        assertThatThrownBy(halfApplied::migrate).isInstanceOf(FlywayException.class);

        assertThat(fingerprint(url))
                .as("columns, indexes and constraints after a failed migration")
                .isEqualTo(before);

        try (Connection connection = DriverManager.getConnection(url, USER, USER)) {
            assertThat(exists(connection,
                    "select to_regclass('public.half_applied_probe') is not null"))
                    .as("table created by the first statement").isFalse();
            assertThat(exists(connection, "select exists (select 1 from information_schema.columns "
                    + "where table_name = 'team' and column_name = 'half_applied_probe')"))
                    .as("column added by the third statement").isFalse();
            assertThat(exists(connection, "select exists (select 1 from pg_indexes "
                    + "where indexname = 'half_applied_probe_idx')"))
                    .as("index created by the fourth statement").isFalse();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Columns, indexes and constraints: everything a half-applied file would have changed. */
    private List<String> fingerprint(String url) throws Exception {
        List<String> shape = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, USER, USER)) {
            shape.addAll(values(connection, "select table_name || '.' || column_name || ' ' "
                    + "|| data_type || ' null=' || is_nullable from information_schema.columns "
                    + "where table_schema = 'public' order by 1"));
            shape.addAll(values(connection,
                    "select indexname from pg_indexes where schemaname = 'public' order by 1"));
            shape.addAll(values(connection,
                    "select conname from pg_constraint where connamespace = 'public'::regnamespace order by 1"));
        }
        return shape;
    }

    private static List<String> values(Connection connection, String sql) throws Exception {
        List<String> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.add(rows.getString(1));
            }
        }
        return result;
    }

    /** A brand-new empty database, created the same way production creates one. */
    private String freshDatabase() {
        String name = "migration_safety_" + System.nanoTime();
        String url = urlFor(name);

        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", url);
        environment.setProperty("spring.datasource.username", USER);
        environment.setProperty("spring.datasource.password", USER);

        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(environment);
        new DatabaseBootstrap().initialize(context);
        context.close();
        return url;
    }

    private static boolean exists(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getBoolean(1);
        }
    }
}
