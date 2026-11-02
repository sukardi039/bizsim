package com.bizsim.sim;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scoring engine without a database: one product, one channel, one project, so every expected
 * number can be checked by hand. Integration behaviour (transactions, idempotency, API shape) lives in
 * {@code RoundFlowApiTest}.
 */
class ScoringEngineTest {

    private static final BigDecimal ZERO = new BigDecimal("0");
    private static final BigDecimal ONE = new BigDecimal("1");

    private final ScoringEngine engine = new ScoringEngine();

    @Test
    void capacity_scales_production_down_to_what_the_workforce_can_build() {
        // 10 workers x 10 units = 100 units, even though 200 were planned.
        ScoringEngine.TeamOutcome outcome = run(team(decision(bd("200"), bd("500")), bd("1000000"),
                "ACTIVE", active("PRJ"), ZERO));

        assertThat(line(outcome).produced()).isEqualByComparingTo("100.00");
        assertThat(line(outcome).planned()).isEqualByComparingTo("200.00");
        assertThat(line(outcome).sold()).isEqualByComparingTo("100.00");
        assertThat(line(outcome).lost()).isEqualByComparingTo("900.00");
        assertThat(outcome.revenue()).isEqualByComparingTo("10000.00");
        assertThat(outcome.profit()).isEqualByComparingTo("5500.00");
        assertThat(outcome.cash()).isEqualByComparingTo("1005500.00");
    }

    @Test
    void materials_are_paid_on_produced_units_while_cogs_is_charged_on_sold_units() {
        // 100 produced at 40 = 4,000 cash out; 100 sold at 40 = 4,000 COGS. Stock would separate them.
        ScoringEngine.TeamOutcome outcome = run(team(decision(bd("100"), ZERO), bd("1000000"),
                "ACTIVE", active("PRJ"), ZERO));

        assertThat(outcome.cashChange()).isEqualByComparingTo("6000.00");
        assertThat(outcome.profit()).isEqualByComparingTo("6000.00");
    }

    @Test
    void production_is_cut_to_the_materials_the_bank_will_pay_for() {
        ScoringEngine.TeamOutcome outcome = run(team(decision(bd("200"), ZERO), bd("100"),
                "ACTIVE", active("PRJ"), ZERO));

        // 100 cash / 40 per unit = 2.5 units, then those 2.5 sell for 100 each.
        assertThat(line(outcome).produced()).isEqualByComparingTo("2.50");
        assertThat(outcome.cash()).isEqualByComparingTo("250.00");
    }

    @Test
    void rnd_completes_only_at_the_end_of_its_fixed_duration() {
        ScoringEngine.RndState started = new ScoringEngine.RndState("PRJ", bd("500"), 1, 1, "ACTIVE", ZERO);
        ScoringEngine.TeamOutcome outcome = runAt(2, team(decision(bd("0"), bd("500")), bd("1000000"),
                "ACTIVE", Map.of("PRJ", started), ZERO));

        ScoringEngine.RndUpdate update = outcome.rndUpdates().get(0);
        assertThat(update.status()).isEqualTo("COMPLETED");
        assertThat(update.monthsInvested()).isEqualTo(2);
        assertThat(update.benefitFactor()).isEqualByComparingTo("1.0000");
    }

    @Test
    void rnd_funded_at_ninety_percent_returns_half_the_advantage() {
        // 400 already invested + 500 = 900 against a hidden cost of 1,000 -> 90% -> factor 0.5.
        ScoringEngine.RndState started = new ScoringEngine.RndState("PRJ", bd("400"), 1, 1, "ACTIVE", ZERO);
        ScoringEngine.TeamOutcome outcome = runAt(2, team(decision(bd("100"), bd("500")), bd("1000000"),
                "ACTIVE", Map.of("PRJ", started), ZERO));

        ScoringEngine.RndUpdate update = outcome.rndUpdates().get(0);
        assertThat(update.status()).isEqualTo("COMPLETED");
        assertThat(update.benefitFactor()).isEqualByComparingTo("0.5000");
    }

    @Test
    void rnd_funded_below_eighty_percent_fails_and_the_money_is_gone() {
        ScoringEngine.RndState started = new ScoringEngine.RndState("PRJ", bd("200"), 1, 1, "ACTIVE", ZERO);
        ScoringEngine.TeamOutcome outcome = runAt(2, team(decision(bd("0"), bd("500")), bd("1000000"),
                "ACTIVE", Map.of("PRJ", started), ZERO));

        ScoringEngine.RndUpdate update = outcome.rndUpdates().get(0);
        assertThat(update.status()).isEqualTo("FAILED");
        assertThat(update.benefitFactor()).isEqualByComparingTo("0.0000");
        assertThat(outcome.rndSpend()).isEqualByComparingTo("500.00");
    }

    @Test
    void a_completed_project_cuts_unit_cost_from_the_next_month() {
        ScoringEngine.RndState done = new ScoringEngine.RndState("PRJ", bd("1000"), 2, 1, "COMPLETED", ONE);
        ScoringEngine.TeamOutcome outcome = run(team(decision(bd("100"), ZERO), bd("1000000"),
                "ACTIVE", Map.of("PRJ", done), ZERO));

        assertThat(line(outcome).unitCost()).isEqualByComparingTo("20.00");
        assertThat(outcome.profit()).isEqualByComparingTo("8000.00");
    }

    @Test
    void an_insolvent_company_produces_nothing_but_still_sells_its_stock() {
        ScoringEngine.TeamOutcome outcome = run(team(decision(bd("100"), ZERO), bd("-500"),
                "INSOLVENT", active("PRJ"), bd("50")));

        assertThat(line(outcome).produced()).isEqualByComparingTo("0.00");
        assertThat(line(outcome).sold()).isEqualByComparingTo("50.00");
        assertThat(outcome.revenue()).isEqualByComparingTo("5000.00");
        // D11: inventory sales lifted cash back above zero, so the company is ACTIVE again.
        assertThat(outcome.status()).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ fixtures

    private ScoringEngine.TeamOutcome run(ScoringEngine.TeamInput team) {
        return runAt(1, team);
    }

    private ScoringEngine.TeamOutcome runAt(int round, ScoringEngine.TeamInput team) {
        return engine.compute(scenario(), round, List.of(team)).teams().get(0);
    }

    private static ScoringEngine.ProductLine line(ScoringEngine.TeamOutcome outcome) {
        return outcome.lines().get(0);
    }

    private static Scenario scenario() {
        return new Scenario("test",
                new Scenario.Params(4, 20, bd("1000000"), bd("50"), ZERO, bd("0.10"), ZERO,
                        bd("50"), bd("1000000"), bd("100000"), ONE, ONE, bd("0.80"), ZERO,
                        List.of(ONE), ZERO, ZERO, ZERO, ZERO,
                        Map.of("PRODUCTION", ZERO), Map.of("PRODUCTION", 10)),
                List.of(new Scenario.ProductSpec("P", "Only", bd("100"), bd("40"), bd("50"),
                        bd("10000"), bd("10"))),
                List.of(new Scenario.ChannelSpec("C", "Channel", ONE, ONE)),
                List.of(new Scenario.ProjectSpec("PRJ", "Process", "P", "UNIT_COST_DOWN",
                        bd("0.5"), bd("1000"), bd("0.2"), 2)));
    }

    private static ScoringEngine.TeamInput team(DecisionPayload decision, BigDecimal cash,
                                                String status,
                                                Map<String, ScoringEngine.RndState> rnd,
                                                BigDecimal stock) {
        return new ScoringEngine.TeamInput(1L, status, cash, bd("50"), ZERO,
                Map.of("PRODUCTION", 10),
                Map.of("P", new ScoringEngine.ProductState("P", bd("100"), bd("50"), bd("40"), stock)),
                rnd, decision);
    }

    private static Map<String, ScoringEngine.RndState> active(String code) {
        return Map.of(code, new ScoringEngine.RndState(code, ZERO, 0, null, "ACTIVE", ZERO));
    }

    private static DecisionPayload decision(BigDecimal units, BigDecimal invest) {
        return new DecisionPayload(
                List.of(new DecisionPayload.ProductRow("P", units)),
                List.of(new DecisionPayload.PriceRow("P", bd("100"))),
                List.of(),
                new DecisionPayload.ManpowerRow(10),
                List.of(new DecisionPayload.RndRow("PRJ", invest)),
                ZERO);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
