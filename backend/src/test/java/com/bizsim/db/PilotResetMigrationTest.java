package com.bizsim.db;

import com.bizsim.support.PostgresTestBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V8__reset_pilot_simulations clears the simulations played under the old scoreboard/finish
 * behaviour.
 *
 * <p>A reset is the easiest migration to get wrong in the direction nobody checks: it can take the
 * user accounts with it and lock the facilitator out of the console. So this asserts both halves --
 * play data is gone, identity and scenario data survive -- rather than only counting deleted rows.
 */
class PilotResetMigrationTest extends PostgresTestBase {

    private static final String USER = "postgres";
    private static final String RESET = "db/migration/V8__reset_pilot_simulations.sql";

    /** Everything that belongs to a played simulation and must disappear. */
    private static final List<String> PLAY_TABLES = List.of(
            "simulation", "run", "round", "team", "player", "decision",
            "round_result", "round_statement");

    /** What the facilitator needs to sign in and start again. */
    private static final List<String> KEPT_TABLES = List.of(
            "tenant", "app_user", "scenario", "product", "scenario_round");

    @Test
    void play_data_is_cleared_and_accounts_survive() throws Exception {
        String url = freshDatabase();
        Flyway.configure().dataSource(url, USER, USER).locations("classpath:db/migration").load().migrate();

        try (Connection connection = DriverManager.getConnection(url, USER, USER)) {
            long tenant = insert(connection,
                    "insert into tenant (code, name) values ('acme', 'Acme') returning id");
            long facilitator = insert(connection, """
                    insert into app_user (tenant_id, email, password_hash, display_name, role)
                    values (%d, 'trainer@acme.test', 'x', 'Trainer', 'FACILITATOR') returning id
                    """.formatted(tenant));
            long player = insert(connection, """
                    insert into app_user (tenant_id, email, password_hash, display_name, role)
                    values (%d, 'one@acme.test', 'x', 'One', 'PLAYER') returning id
                    """.formatted(tenant));
            long simulation = insert(connection, """
                    insert into simulation (tenant_id, name, scenario_key, status, facilitator_id)
                    values (%d, 'Pilot', 'standard-v1', 'READY', %d) returning id
                    """.formatted(tenant, facilitator));
            long run = insert(connection, """
                    insert into run (tenant_id, simulation_id, scenario_key, status, current_round, rounds)
                    values (%d, %d, 'standard-v1', 'OPEN', 2, 2) returning id
                    """.formatted(tenant, simulation));
            long team = insert(connection, """
                    insert into team (run_id, name, company_name)
                    values (%d, 'A', 'A Inc') returning id
                    """.formatted(run));
            insert(connection, """
                    insert into player (run_id, team_id, user_id, role_key)
                    values (%d, %d, %d, 'CEO') returning id
                    """.formatted(run, team, player));
            long round = insert(connection, """
                    insert into round (run_id, number, status) values (%d, 1, 'COMPUTED') returning id
                    """.formatted(run));
            insert(connection, """
                    insert into decision (round_id, team_id, role_key, payload, submitted_by)
                    values (%d, %d, 'CEO', '{}'::jsonb, %d) returning id
                    """.formatted(round, team, player));
            insert(connection, """
                    insert into round_result (round_id, team_id, score, total_score)
                    values (%d, %d, '{}'::jsonb, 0) returning id
                    """.formatted(round, team));
            insert(connection, """
                    insert into round_statement (round_id, team_id, revenue, cogs, payroll, marketing,
                        rnd_spend, fixed_cost, holding_cost, hiring_cost, capacity_cost, profit,
                        cash_after, brand_after, lost_sales, capacity_used, capacity_available, detail)
                    values (%d, %d, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 50, 0, 0, 0, '{}'::jsonb)
                    returning round_id
                    """.formatted(round, team));

            assertThat(count(connection, "simulation")).isEqualTo(1);

            applyReset(connection);

            for (String table : PLAY_TABLES) {
                assertThat(count(connection, table))
                        .as("%s after the reset", table)
                        .isZero();
            }
            for (String table : KEPT_TABLES) {
                assertThat(count(connection, table))
                        .as("%s must survive the reset", table)
                        .isPositive();
            }

            // The facilitator can sign in and start a brand-new simulation afterwards.
            long fresh = insert(connection, """
                    insert into simulation (tenant_id, name, scenario_key, status, facilitator_id)
                    values (%d, 'Fresh cohort', 'standard-v1', 'READY', %d) returning id
                    """.formatted(tenant, facilitator));
            assertThat(fresh).isPositive();
            assertThat(count(connection, "simulation")).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void applyReset(Connection connection) throws IOException, SQLException {
        String sql = new ClassPathResource(RESET).getContentAsString(StandardCharsets.UTF_8);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select count(*) from " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static long insert(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet keys = statement.executeQuery(sql)) {
            keys.next();
            return keys.getLong(1);
        }
    }

    /** A brand-new empty database, created the same way production creates one. */
    private String freshDatabase() {
        String name = "pilot_reset_" + UUID.randomUUID().toString().replace("-", "");
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
}
