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
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V7__finish_completed_runs repairs runs that predate the finish transition (9dbbbdc).
 *
 * <p>The migration is a data repair, so it cannot be proven by "the schema still looks right": the
 * dangerous case is closing a session that is still being played. This test sets up the three shapes
 * that exist in a real database after the deploy and checks each one.
 */
class LegacyRunFinishMigrationTest extends PostgresTestBase {

    private static final String USER = "postgres";
    private static final String REPAIR = "db/migration/V7__finish_completed_runs.sql";

    @Test
    void only_runs_whose_final_round_is_computed_are_closed() throws Exception {
        String url = freshDatabase();
        Flyway.configure().dataSource(url, USER, USER).locations("classpath:db/migration").load().migrate();

        long finishedBefore;
        long playedToTheEnd;
        long lastRoundStillOpen;
        long onlyOneOfTwoPlayed;

        try (Connection connection = DriverManager.getConnection(url, USER, USER)) {
            long tenant = insert(connection,
                    "insert into tenant (code, name) values ('repair', 'Repair') returning id");
            long facilitator = insert(connection, """
                    insert into app_user (tenant_id, email, password_hash, display_name, role)
                    values (%d, 'trainer@repair.test', 'x', 'Trainer', 'FACILITATOR') returning id
                    """.formatted(tenant));
            long simulation = insert(connection, """
                    insert into simulation (tenant_id, name, scenario_key, status, facilitator_id)
                    values (%d, 'Repair', 'standard-v1', 'READY', %d) returning id
                    """.formatted(tenant, facilitator));

            // The facilitator's finished-and-stamped session: a rerun must not rewrite its timestamp.
            finishedBefore = run(connection, tenant, simulation, "FINISHED", 2, 2);
            connection.createStatement().execute(
                    "update run set finished_at = timestamp '2026-10-01 09:00:00+00' where id = "
                            + finishedBefore);
            computedRound(connection, finishedBefore, 1, "2026-10-01 08:00:00+00");
            computedRound(connection, finishedBefore, 2, "2026-10-01 08:30:00+00");

            // 8 of 8 played and computed, still OPEN: this is the broken row V7 exists for.
            playedToTheEnd = run(connection, tenant, simulation, "OPEN", 2, 2);
            computedRound(connection, playedToTheEnd, 1, "2026-11-01 09:00:00+00");
            computedRound(connection, playedToTheEnd, 2, "2026-11-01 09:30:00+00");

            // The class is on its last round but has not submitted it: still in progress.
            lastRoundStillOpen = run(connection, tenant, simulation, "OPEN", 2, 2);
            computedRound(connection, lastRoundStillOpen, 1, "2026-11-02 09:00:00+00");
            openRound(connection, lastRoundStillOpen, 2);

            // Mid-session: the final round does not even exist yet.
            onlyOneOfTwoPlayed = run(connection, tenant, simulation, "OPEN", 1, 2);
            computedRound(connection, onlyOneOfTwoPlayed, 1, "2026-11-02 11:00:00+00");

            applyRepair(connection);
            applyRepair(connection); // idempotent: a second run must change nothing
        }

        try (Connection connection = DriverManager.getConnection(url, USER, USER)) {
            assertThat(status(connection, playedToTheEnd))
                    .as("session played to the end, computed, left OPEN by the old code")
                    .isEqualTo("FINISHED");
            assertThat(finishedAt(connection, playedToTheEnd))
                    .as("finished_at is when the last results were written, not when V7 ran")
                    .isEqualTo(Timestamp.valueOf("2026-11-01 09:30:00"));

            assertThat(status(connection, lastRoundStillOpen))
                    .as("last round open: the class is still playing, do not freeze it")
                    .isEqualTo("OPEN");
            assertThat(finishedAt(connection, lastRoundStillOpen)).isNull();

            assertThat(status(connection, onlyOneOfTwoPlayed))
                    .as("a run with rounds left must not be closed")
                    .isEqualTo("OPEN");

            assertThat(finishedAt(connection, finishedBefore))
                    .as("an already finished run keeps its original stamp")
                    .isEqualTo(Timestamp.valueOf("2026-10-01 09:00:00"));
        }
    }

    // ------------------------------------------------------------------ fixture

    private static long run(Connection connection, long tenant, long simulation, String status,
                            int currentRound, int rounds) throws SQLException {
        return insert(connection, """
                insert into run (tenant_id, simulation_id, scenario_key, status, current_round, rounds)
                values (%d, %d, 'standard-v1', '%s', %d, %d) returning id
                """.formatted(tenant, simulation, status, currentRound, rounds));
    }

    private static void computedRound(Connection connection, long runId, int number, String computedAt)
            throws SQLException {
        insert(connection, """
                insert into round (run_id, number, status, closed_at)
                values (%d, %d, 'COMPUTED', timestamp '%s') returning id
                """.formatted(runId, number, computedAt));
    }

    private static void openRound(Connection connection, long runId, int number) throws SQLException {
        insert(connection, """
                insert into round (run_id, number, status) values (%d, %d, 'OPEN') returning id
                """.formatted(runId, number));
    }

    private static void applyRepair(Connection connection) throws IOException, SQLException {
        String sql = new ClassPathResource(REPAIR).getContentAsString(StandardCharsets.UTF_8);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long insert(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet keys = statement.executeQuery(sql)) {
            keys.next();
            return keys.getLong(1);
        }
    }

    private static String status(Connection connection, long runId) throws SQLException {
        return scalar(connection, "select status from run where id = " + runId);
    }

    private static Timestamp finishedAt(Connection connection, long runId) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select finished_at from run where id = " + runId)) {
            rows.next();
            return rows.getTimestamp(1);
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    /** A brand-new empty database, created the same way production creates one. */
    private String freshDatabase() {
        String name = "legacy_finish_" + UUID.randomUUID().toString().replace("-", "");
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
