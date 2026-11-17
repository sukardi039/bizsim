package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D99: the share a company held last round weighs against this round's contest.
 *
 * <p>Two identical companies are the clearest case: the contest is 50/50, so every unit that moves
 * comes from the carried share. With λ = 0.5 and 80/20 held, the allocation must be 65/35 of the
 * served market — half of each — and the pieces must still add up to the market exactly.
 */
@SpringBootTest
class InertiaTest extends PostgresTestBase {

    @Autowired
    private ScenarioRepository scenarios;
    @Autowired
    private JdbcTemplate jdbc;

    private final ScoringEngine engine = new ScoringEngine();
    private final EnvironmentService.RoundEnvironment neutral =
            EnvironmentService.RoundEnvironment.neutral(1, java.util.Set.of("P"));

    @Test
    void inertia_zero_reproduces_the_contest_exactly_even_when_a_previous_share_is_supplied() {
        List<ScoringEngine.TeamInput> teams = identicalTeams();

        ScoringEngine.RoundResult withoutHistory = engine.compute(scenario("0"), neutral, teams);
        ScoringEngine.RoundResult withHistory = engine.compute(scenario("0"), neutral, teams,
                Map.of(1L, Map.of("P", 0.8), 2L, Map.of("P", 0.2)));

        assertThat(demandOf(withHistory, 1L)).isEqualByComparingTo(demandOf(withoutHistory, 1L));
        assertThat(demandOf(withHistory, 2L)).isEqualByComparingTo(demandOf(withoutHistory, 2L));
        assertThat(withHistory.teams().get(0).profit())
                .isEqualByComparingTo(withoutHistory.teams().get(0).profit());
    }

    @Test
    void half_of_last_rounds_share_is_carried_into_this_rounds_allocation() {
        List<ScoringEngine.TeamInput> teams = identicalTeams();
        BigDecimal target = servedMarket(2);

        ScoringEngine.RoundResult result = engine.compute(scenario("0.5"), neutral, teams,
                Map.of(1L, Map.of("P", 0.8), 2L, Map.of("P", 0.2)));

        // 0.5 x 0.80 + 0.5 x 0.50 = 0.65, and 0.5 x 0.20 + 0.5 x 0.50 = 0.35.
        assertThat(demandOf(result, 1L))
                .isEqualByComparingTo(share(target, "0.65"));
        assertThat(demandOf(result, 2L))
                .isEqualByComparingTo(share(target, "0.35"));
        assertThat(demandOf(result, 1L).add(demandOf(result, 2L))).isEqualByComparingTo(target);
    }

    @Test
    void full_inertia_hands_the_held_share_back_untouched() {
        List<ScoringEngine.TeamInput> teams = identicalTeams();
        BigDecimal target = servedMarket(2);

        ScoringEngine.RoundResult result = engine.compute(scenario("1"), neutral, teams,
                Map.of(1L, Map.of("P", 0.8), 2L, Map.of("P", 0.2)));

        assertThat(demandOf(result, 1L)).isEqualByComparingTo(share(target, "0.80"));
        assertThat(demandOf(result, 2L)).isEqualByComparingTo(share(target, "0.20"));
    }

    @Test
    void a_stocked_out_previous_round_still_fills_the_market_exactly() {
        List<ScoringEngine.TeamInput> teams = identicalTeams();
        BigDecimal target = servedMarket(2);

        // last month the two companies actually sold 50% and 25% of the served market between them —
        // one stocked out. The blend must not shrink the market because of it.
        ScoringEngine.RoundResult result = engine.compute(scenario("0.5"), neutral, teams,
                Map.of(1L, Map.of("P", 0.50), 2L, Map.of("P", 0.25)));

        assertThat(demandOf(result, 1L).add(demandOf(result, 2L))).isEqualByComparingTo(target);
        assertThat(demandOf(result, 1L)).isGreaterThan(demandOf(result, 2L));
        // 0.5 x 0.50 + 0.5 x 0.50 = 0.50 against 0.5 x 0.25 + 0.5 x 0.50 = 0.375: normalising gives
        // 57.14% rather than the 65% a full 80/20 history would have won.
        assertThat(demandOf(result, 1L)).isLessThan(share(target, "0.65"));
    }

    @Test
    void no_previous_round_means_no_loyalty_even_when_the_template_asks_for_it() {
        List<ScoringEngine.TeamInput> teams = identicalTeams();
        BigDecimal target = servedMarket(2);

        ScoringEngine.RoundResult result = engine.compute(scenario("0.5"), neutral, teams, Map.of());

        assertThat(demandOf(result, 1L)).isEqualByComparingTo(share(target, "0.50"));
        assertThat(demandOf(result, 2L)).isEqualByComparingTo(share(target, "0.50"));
    }

    @Test
    void the_loader_defaults_inertia_to_zero_and_rejects_a_weight_above_one() {
        String original = jdbc.queryForObject(
                "select definition::text from scenario where key = 'standard-v1'", String.class);
        try {
            jdbc.update("update scenario set definition = definition - 'inertia' where key = 'standard-v1'");
            assertThat(scenarios.load("standard-v1").params().inertia()).isEqualByComparingTo("0");

            jdbc.update("update scenario set definition = jsonb_set(definition, '{inertia}', '0.5') "
                    + "where key = 'standard-v1'");
            assertThat(scenarios.load("standard-v1").params().inertia()).isEqualByComparingTo("0.5");

            jdbc.update("update scenario set definition = jsonb_set(definition, '{inertia}', '1.5') "
                    + "where key = 'standard-v1'");
            assertThatThrownBy(() -> scenarios.load("standard-v1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("inertia");
        } finally {
            jdbc.update("update scenario set definition = ?::jsonb where key = 'standard-v1'", original);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static BigDecimal demandOf(ScoringEngine.RoundResult result, long teamId) {
        return result.teams().stream().filter(o -> o.teamId() == teamId).findFirst().orElseThrow()
                .lines().get(0).demand();
    }

    /** What the two companies are allocated in total: the pool less the market nobody sees. */
    private BigDecimal servedMarket(int companies) {
        BigDecimal pool = engine.marketPool(scenario("0"), neutral, companies);
        return pool.multiply(BigDecimal.ONE.subtract(scenario("0").params().baselineShare()))
                .setScale(0, RoundingMode.HALF_UP);
    }

    private static BigDecimal share(BigDecimal target, String fraction) {
        return target.multiply(new BigDecimal(fraction)).setScale(0, RoundingMode.DOWN);
    }

    /** Same price, same quality, same brand: only the carried share can move the split. */
    private static List<ScoringEngine.TeamInput> identicalTeams() {
        return List.of(team(1L), team(2L));
    }

    private static ScoringEngine.TeamInput team(long id) {
        DecisionPayload decision = new DecisionPayload(
                List.of(new DecisionPayload.ProductRow("P", BigDecimal.valueOf(50))),
                List.of(new DecisionPayload.PriceRow("P", BigDecimal.valueOf(100))),
                List.of(),
                new DecisionPayload.ManpowerRow(10),
                List.of(), List.of(), BigDecimal.ZERO, false);
        return new ScoringEngine.TeamInput(id, "ACTIVE", BigDecimal.valueOf(1_000_000),
                BigDecimal.valueOf(50), BigDecimal.ZERO,
                Map.of("PRODUCTION", 10),
                Map.of("P", new ScoringEngine.ProductState("P", BigDecimal.valueOf(100),
                        BigDecimal.valueOf(50), BigDecimal.valueOf(40), BigDecimal.ZERO, BigDecimal.ZERO)),
                Map.of(), decision);
    }

    private static Scenario scenario(String inertia) {
        BigDecimal z = BigDecimal.ZERO;
        BigDecimal one = BigDecimal.ONE;
        return new Scenario("test",
                new Scenario.Params(4, 20, BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(50), z,
                        new BigDecimal("0.10"), z, BigDecimal.valueOf(50), BigDecimal.valueOf(1_000_000),
                        BigDecimal.valueOf(100_000), one, one, new BigDecimal("0.80"), z,
                        List.of(one), z, z, z, z,
                        Map.of("PRODUCTION", z), Map.of("PRODUCTION", 10),
                        BigDecimal.valueOf(15_000_000), 2, new BigDecimal("0.08"), 2, 4,
                        new BigDecimal("0.02"), new BigDecimal("0.01"),
                        false, z, false, 0, false, new BigDecimal(inertia)),
                List.of(new Scenario.ProductSpec("P", "Only", BigDecimal.valueOf(100),
                        BigDecimal.valueOf(40), BigDecimal.valueOf(50), BigDecimal.valueOf(10_000),
                        BigDecimal.valueOf(10), one, z, null, z, Map.of())),
                List.of(new Scenario.ChannelSpec("C", "Channel", one, one)),
                List.of(), List.of(), List.of(), List.of());
    }
}
