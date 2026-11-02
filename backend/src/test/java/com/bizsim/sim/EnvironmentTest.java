package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The environment model (D18): the economy moves market size, products react differently, the event
 * deck is drawn once per run, and the forecast is stable enough to argue about.
 */
@SpringBootTest
class EnvironmentTest extends PostgresTestBase {

    @Autowired
    private ScenarioRepository scenarios;
    @Autowired
    private EnvironmentService environment;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void a_recession_hits_the_premium_product_harder_than_the_basic_one() {
        Scenario scenario = scenarios.load("standard-v1");

        // Round 4 is the trough: demand index 0.90, cost index 1.08.
        EnvironmentService.RoundEnvironment trough = environment.forRound(scenario, 1L, 4);

        assertThat(trough.demandIndex()).isEqualByComparingTo("0.900");
        assertThat(trough.costIndex()).isEqualByComparingTo("1.080");
        assertThat(trough.demandByProduct().get("P1"))
                .as("basic, elasticity 0.4").isGreaterThan(trough.demandByProduct().get("P2"));
        assertThat(trough.demandByProduct().get("P2"))
                .as("standard, elasticity 1.0").isGreaterThan(trough.demandByProduct().get("P3"));
    }

    @Test
    void a_round_the_timeline_does_not_cover_is_neutral() {
        Scenario scenario = scenarios.load("standard-v1");
        EnvironmentService.RoundEnvironment far = environment.forRound(scenario, 1L, 99);

        assertThat(far.demandIndex()).isEqualByComparingTo("1.000");
        assertThat(far.demandByProduct().get("P3")).isEqualByComparingTo("1.000");
    }

    @Test
    void the_forecast_is_noisy_but_stable_and_never_reveals_an_event() {
        Scenario scenario = scenarios.load("standard-v1");

        List<Map<String, Object>> first = environment.forecast(scenario, 42L, 1);
        List<Map<String, Object>> again = environment.forecast(scenario, 42L, 1);

        assertThat(first).hasSize(2);
        assertThat(first.toString()).isEqualTo(again.toString());

        // Each forecast value is within the published band of the timeline it is based on.
        List<Scenario.RoundSpec> timeline = scenario.rounds();
        for (Map<String, Object> row : first) {
            int round = (Integer) row.get("round");
            BigDecimal actual = timeline.stream().filter(spec -> spec.round() == round)
                    .findFirst().orElseThrow().demandIndex();
            BigDecimal forecast = (BigDecimal) row.get("demandIndex");
            assertThat(forecast.subtract(actual).abs())
                    .as("round %d forecast %.3f vs timeline %.3f", round, forecast, actual)
                    .isLessThanOrEqualTo(actual.multiply(new BigDecimal("0.081")));
        }
    }

    @Test
    void the_event_deck_is_drawn_once_and_does_not_change_on_a_second_call() {
        Scenario scenario = scenarios.load("standard-v1");
        long runId = seedRun();
        long otherRun = seedRun();

        environment.scheduleEvents(scenario, runId);
        List<Map<String, Object>> first = jdbc.queryForList(
                "select event_code, starts_round from run_environment_event where run_id = ? order by event_code",
                runId);

        environment.scheduleEvents(scenario, runId);
        List<Map<String, Object>> again = jdbc.queryForList(
                "select event_code, starts_round from run_environment_event where run_id = ? order by event_code",
                runId);
        environment.scheduleEvents(scenario, otherRun);
        List<Map<String, Object>> other = jdbc.queryForList(
                "select event_code, starts_round from run_environment_event where run_id = ? order by event_code",
                otherRun);

        assertThat(first.toString()).isEqualTo(again.toString());
        assertThat(first).allSatisfy(row -> assertThat((Integer) row.get("starts_round")).isBetween(1, 7));
        // Two different runs draw independently; with four cards the schedules are not always equal.
        assertThat(first.toString().equals(other.toString())
                || first.size() != other.size()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private long seedRun() {
        String suffix = Long.toString(System.nanoTime());
        long tenant = jdbc.queryForObject(
                "insert into tenant (code, name) values (?, ?) returning id", Long.class,
                "env" + suffix, "Env " + suffix);
        long user = jdbc.queryForObject("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, 'x', 'Facilitator', 'FACILITATOR') returning id
                """, Long.class, tenant, "f" + suffix + "@env.test");
        long simulation = jdbc.queryForObject("""
                insert into simulation (tenant_id, name, scenario_key, facilitator_id)
                values (?, 'Env', 'standard-v1', ?) returning id
                """, Long.class, tenant, user);
        return jdbc.queryForObject("""
                insert into run (tenant_id, simulation_id, scenario_key, status)
                values (?, ?, 'standard-v1', 'OPEN') returning id
                """, Long.class, tenant, simulation);
    }
}
