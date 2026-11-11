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

    @Test
    void partial_benefit_switched_off_needs_full_funding() {
        Scenario hard = scenario(project(true, "LINEAR", ONE, "REFUND"));
        Scenario strict = scenario(project(false, "LINEAR", ONE, "REFUND"));
        ScoringEngine.RndState started = new ScoringEngine.RndState("PRJ", bd("400"), 1, 1, "ACTIVE", ZERO);

        // 90% funding: partial credit under the switch, failure without it.
        assertThat(runAt(2, team(decision(bd("0"), bd("500")), bd("1000000"), "ACTIVE",
                Map.of("PRJ", started), ZERO), hard).rndUpdates().get(0).status()).isEqualTo("COMPLETED");
        ScoringEngine.RndUpdate strictResult = runAt(2, team(decision(bd("0"), bd("500")),
                bd("1000000"), "ACTIVE", Map.of("PRJ", started), ZERO), strict).rndUpdates().get(0);
        assertThat(strictResult.status()).isEqualTo("FAILED");
        assertThat(strictResult.benefitFactor()).isEqualByComparingTo("0.0000");
    }

    @Test
    void power_curve_makes_the_last_dollars_worth_more() {
        // 90% funding -> progress 0.5 -> 0.5^2 = 0.25 of the advantage, versus 0.5 on the linear curve.
        Scenario parabolic = scenario(project(true, "POWER", bd("2.0"), "REFUND"));
        ScoringEngine.RndState started = new ScoringEngine.RndState("PRJ", bd("400"), 1, 1, "ACTIVE", ZERO);

        ScoringEngine.RndUpdate update = runAt(2, team(decision(bd("0"), bd("500")), bd("1000000"),
                "ACTIVE", Map.of("PRJ", started), ZERO), parabolic).rndUpdates().get(0);

        assertThat(update.status()).isEqualTo("COMPLETED");
        assertThat(update.benefitFactor()).isEqualByComparingTo("0.2500");
    }

    @Test
    void chance_curve_is_a_hidden_threshold_and_replays_identically() {
        Scenario chance = scenario(project(true, "CHANCE", ONE, "REFUND"));
        ScoringEngine.RndState started = new ScoringEngine.RndState("PRJ", bd("400"), 1, 1, "ACTIVE", ZERO);
        ScoringEngine.TeamInput input = team(decision(bd("0"), bd("500")), bd("1000000"), "ACTIVE",
                Map.of("PRJ", started), ZERO);

        ScoringEngine.RndUpdate first = runAt(2, input, chance).rndUpdates().get(0);
        ScoringEngine.RndUpdate again = runAt(2, input, chance).rndUpdates().get(0);

        assertThat(first.threshold()).isEqualByComparingTo(again.threshold());
        assertThat(first.status()).isEqualTo(again.status());
        assertThat(first.threshold()).isBetween(new BigDecimal("0.8000"), new BigDecimal("1.0000"));
        // The outcome follows from the threshold, not from a fresh roll: 90% funding.
        boolean expected = new BigDecimal("0.900000").compareTo(first.threshold()) >= 0;
        assertThat(first.status()).isEqualTo(expected ? "COMPLETED" : "FAILED");
    }

    @Test
    void chance_curve_draws_a_different_threshold_per_company_but_never_a_certainty() {
        Scenario chance = scenario(project(true, "CHANCE", ONE, "REFUND"));
        java.util.Set<String> thresholds = new java.util.HashSet<>();

        for (long id = 1; id <= 20; id++) {
            ScoringEngine.TeamInput input = new ScoringEngine.TeamInput(id, "ACTIVE", bd("1000000"),
                    bd("1000000"), ZERO, Map.of("PRODUCTION", 10),
                    Map.of("P", new ScoringEngine.ProductState("P", bd("100"), bd("50"), bd("40"), ZERO, ZERO)),
                    Map.of("PRJ", new ScoringEngine.RndState("PRJ", bd("400"), 1, 1, "ACTIVE", ZERO)),
                    decision(bd("0"), bd("500")));
            thresholds.add(runAt(2, input, chance).rndUpdates().get(0).threshold().toPlainString());
        }
        assertThat(thresholds).hasSizeGreaterThan(10);
    }

    @Test
    void overfunding_is_refunded_or_lost_as_the_project_says() {
        ScoringEngine.RndState started = new ScoringEngine.RndState("PRJ", bd("0"), 1, 1, "ACTIVE", ZERO);

        ScoringEngine.TeamOutcome refunded = runAt(2, team(decision(bd("0"), bd("2000")), bd("1000000"),
                "ACTIVE", Map.of("PRJ", started), ZERO), scenario(project(true, "LINEAR", ONE, "REFUND")));
        ScoringEngine.TeamOutcome lost = runAt(2, team(decision(bd("0"), bd("2000")), bd("1000000"),
                "ACTIVE", Map.of("PRJ", started), ZERO), scenario(project(true, "LINEAR", ONE, "LOST")));

        // Cash: 1,000,000 - 2,000 spent, + 1,000 returned only under REFUND.
        assertThat(refunded.cash()).isEqualByComparingTo("999000.00");
        assertThat(refunded.rndUpdates().get(0).invested()).isEqualByComparingTo("1000.00");
        assertThat(lost.cash()).isEqualByComparingTo("998000.00");
        assertThat(lost.rndUpdates().get(0).invested()).isEqualByComparingTo("2000.00");
    }

    @Test
    void the_environment_scales_the_market_and_the_input_costs() {
        // Half the demand, double the material cost: the two knobs must reach different places.
        EnvironmentService.RoundEnvironment harsh = new EnvironmentService.RoundEnvironment(1,
                bd("0.5"), bd("2.0"), ONE, ZERO, null, Map.of(), Map.of("P", bd("0.5")), List.of());

        ScoringEngine.TeamOutcome outcome = engine.compute(scenario(), harsh,
                List.of(team(decision(bd("100"), ZERO), bd("1000000"), "ACTIVE", active("PRJ"), ZERO)))
                .teams().get(0);

        assertThat(line(outcome).unitCost()).isEqualByComparingTo("80.00");
        assertThat(line(outcome).demand())
                .as("pool 1,000 x environment multiplier 0.5").isEqualByComparingTo("500.00");
        assertThat(line(outcome).sold()).isEqualByComparingTo("100.00");
    }

    @Test
    void demand_is_split_in_whole_pieces_that_add_up_to_the_market_the_cohort_serves() {
        EnvironmentService.RoundEnvironment neutral =
                EnvironmentService.RoundEnvironment.neutral(1, java.util.Set.of("P"));
        Scenario scenario = scenario();
        double baseline = scenario.params().baselineShare().doubleValue();

        ScoringEngine.TeamInput first = team(decision(bd("0"), ZERO), bd("1000000"), "ACTIVE",
                active("PRJ"), ZERO);
        ScoringEngine.TeamInput second = new ScoringEngine.TeamInput(2L, "ACTIVE", bd("1000000"),
                bd("50"), ZERO, Map.of("PRODUCTION", 10),
                Map.of("P", new ScoringEngine.ProductState("P", bd("100"), bd("50"), bd("40"), ZERO, ZERO)),
                active("PRJ"), decision(bd("0"), ZERO));

        ScoringEngine.RoundResult result = engine.compute(scenario, neutral, List.of(first, second));

        BigDecimal total = ZERO;
        for (ScoringEngine.TeamOutcome outcome : result.teams()) {
            BigDecimal demand = line(outcome).demand();
            assertThat(demand.stripTrailingZeros().scale()).as("whole pieces").isLessThanOrEqualTo(0);
            total = total.add(demand);
        }
        // The cohort serves (1 - baselineShare) of the pool; the split adds up to it exactly, so no
        // unit is created or lost by rounding.
        BigDecimal served = engine.marketPool(scenario, neutral, 2)
                .multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(baseline)))
                .setScale(0, java.math.RoundingMode.HALF_UP);
        assertThat(total).isEqualByComparingTo(served);
    }

    @Test
    void a_products_market_is_its_class_plus_the_stated_share_of_the_others() {
        // D85: each product competes for its own class's purchasable demand, plus a stated slice of
        // the other classes'. Mass: 1,000 buyers at 0.5 x 0.8 = 400 pieces. Affluent: 200 at 1.0.
        Scenario scenario = segmentScenario();
        EnvironmentService.RoundEnvironment environment = new EnvironmentService.RoundEnvironment(1,
                ONE, ONE, ONE, ZERO, "Stable",
                Map.of("SEG-BASIC", new EnvironmentService.SegmentState("SEG-BASIC", "Mass",
                                bd("0.500000"), bd("0.800000")),
                        "SEG-PREMIUM", new EnvironmentService.SegmentState("SEG-PREMIUM", "Affluent",
                                ONE, ONE)),
                Map.of(), List.of());

        // Mass product: 1,000 x 0.5 x 0.8 = 400. Affluent product: 200 x 1.0, plus 10% of the mass
        // class's 400 = 240.
        assertThat(engine.marketPool(scenario, environment, 10)).isEqualByComparingTo("640");
    }

    // ------------------------------------------------------------------ fixtures

    @Test
    void a_quality_purchase_raises_the_level_and_is_charged_as_an_expense() {
        // D15: one point at the template's rate, bought with this month's decision.
        ScoringEngine.TeamOutcome outcome = runAt(1,
                team(qualityDecision(bd("60000000")), bd("1000000000"), "ACTIVE", active("PRJ"), ZERO),
                scenarioWithQuality());

        assertThat(line(outcome).quality()).isEqualByComparingTo("51.00");
        assertThat(outcome.qualitySpend()).isEqualByComparingTo("60000000.00");
        // It behaves like R&D spend, not like a machine: both profit and cash carry it.
        assertThat(outcome.profit()).isEqualByComparingTo("-60000000.00");
        assertThat(outcome.cash()).isEqualByComparingTo("940000000.00");
    }

    @Test
    void quality_stops_at_one_hundred_and_the_rest_of_the_money_is_not_charged() {
        // 100 points of money against 50 points of room: only the room is sold.
        ScoringEngine.TeamOutcome outcome = runAt(1,
                team(qualityDecision(bd("6000000000")), bd("10000000000"), "ACTIVE", active("PRJ"), ZERO),
                scenarioWithQuality());

        assertThat(line(outcome).quality()).isEqualByComparingTo("100.00");
        assertThat(outcome.qualitySpend()).isEqualByComparingTo("3000000000.00");
    }

    @Test
    void a_template_that_does_not_offer_quality_ignores_the_money() {
        ScoringEngine.TeamOutcome outcome = runAt(1,
                team(qualityDecision(bd("60000000")), bd("1000000000"), "ACTIVE", active("PRJ"), ZERO),
                scenario());

        assertThat(line(outcome).quality()).isEqualByComparingTo("50.00");
        assertThat(outcome.qualitySpend()).isEqualByComparingTo("0.00");
    }

    private ScoringEngine.TeamOutcome run(ScoringEngine.TeamInput team) {
        return runAt(1, team);
    }

    private ScoringEngine.TeamOutcome runAt(int round, ScoringEngine.TeamInput team) {
        return runAt(round, team, scenario());
    }

    private ScoringEngine.TeamOutcome runAt(int round, ScoringEngine.TeamInput team, Scenario scenario) {
        return engine.compute(scenario,
                EnvironmentService.RoundEnvironment.neutral(round, java.util.Set.of("P")),
                List.of(team)).teams().get(0);
    }

    private static ScoringEngine.ProductLine line(ScoringEngine.TeamOutcome outcome) {
        return outcome.lines().get(0);
    }

    private static Scenario scenario() {
        return scenario(project(true, "LINEAR", ONE, "REFUND"));
    }

    private static Scenario scenario(Scenario.ProjectSpec project) {
        return new Scenario("test",
                new Scenario.Params(4, 20, bd("1000000"), bd("50"), ZERO, bd("0.10"), ZERO,
                        bd("50"), bd("1000000"), bd("100000"), ONE, ONE, bd("0.80"), ZERO,
                        List.of(ONE), ZERO, ZERO, ZERO, ZERO,
                        Map.of("PRODUCTION", ZERO), Map.of("PRODUCTION", 10),
                        bd("15000000"), 2, bd("0.08"), 2, 4, bd("0.02"), bd("0.01"),
                        false, ZERO, false, 0, false),
                List.of(new Scenario.ProductSpec("P", "Only", bd("100"), bd("40"), bd("50"),
                        bd("10000"), bd("10"), ONE, ZERO, null, ZERO)),
                List.of(new Scenario.ChannelSpec("C", "Channel", ONE, ONE)),
                List.of(project), List.of(), List.of(), List.of());
    }

    /**
     * Two products, one per class. Params are the single-product fixture's: only the classes and the
     * products matter to the market formula.
     */
    private static Scenario segmentScenario() {
        Scenario base = scenario();
        return new Scenario(base.key(), base.params(),
                List.of(new Scenario.ProductSpec("PA", "Mass", bd("100"), bd("40"), bd("50"),
                                bd("1000"), bd("10"), ONE, ZERO, "SEG-BASIC", ZERO),
                        new Scenario.ProductSpec("PB", "Affluent", bd("100"), bd("40"), bd("50"),
                                bd("200"), bd("10"), ONE, ZERO, "SEG-PREMIUM", bd("0.10"))),
                base.channels(), base.projects(), base.rounds(),
                List.of(new Scenario.SegmentSpec("SEG-BASIC", "Mass", null, null, null,
                                bd("1000"), ONE, ONE, ZERO, 1),
                        new Scenario.SegmentSpec("SEG-PREMIUM", "Affluent", null, null, null,
                                bd("200"), ONE, ONE, ZERO, 2)),
                base.events());
    }

    private static Scenario scenarioWithQuality() {
        Scenario base = scenario();
        Scenario.Params p = base.params();
        Scenario.Params q = new Scenario.Params(p.rounds(), p.roundMinutes(), p.startingCash(),
                p.startingBrand(), p.fixedCostPerMonth(), p.brandDecay(), p.holdingCostPerUnit(),
                p.referenceQuality(), p.referenceSpend(), p.machineCostPerWorker(), p.hiringCostMonths(),
                p.severanceMonths(), p.minRndBenefitRatio(), p.baselineShare(), p.seasonality(),
                p.priceElasticity(), p.qualityElasticity(), p.brandElasticity(), p.marketingElasticity(),
                p.salaries(), p.startingStaff(), p.marketReportCost(), p.forecastHorizon(),
                p.forecastBand(), p.cycles(), p.cycleLength(), p.cycleDemandDrift(), p.cycleCostDrift(),
                true, bd("60000000"), false, 0, false);
        return new Scenario(base.key(), q, base.products(), base.channels(), base.projects(),
                base.rounds(), base.segments(), base.events());
    }

    private static DecisionPayload qualityDecision(BigDecimal invest) {
        return new DecisionPayload(
                List.of(new DecisionPayload.ProductRow("P", ZERO)),
                List.of(new DecisionPayload.PriceRow("P", bd("100"))),
                List.of(),
                new DecisionPayload.ManpowerRow(10),
                List.of(),
                List.of(new DecisionPayload.QualityRow("P", invest)),
                ZERO, false);
    }

    private static Scenario.ProjectSpec project(boolean partialBenefit, String curve,
                                                BigDecimal exponent, String overfunding) {
        return new Scenario.ProjectSpec("PRJ", "Process", "en", "P", "UNIT_COST_DOWN", bd("0.5"), bd("1000"),
                bd("0.2"), 2, partialBenefit, curve, exponent, overfunding);
    }

    private static ScoringEngine.TeamInput team(DecisionPayload decision, BigDecimal cash,
                                                String status,
                                                Map<String, ScoringEngine.RndState> rnd,
                                                BigDecimal stock) {
        return new ScoringEngine.TeamInput(1L, status, cash, bd("50"), ZERO,
                Map.of("PRODUCTION", 10),
                Map.of("P", new ScoringEngine.ProductState("P", bd("100"), bd("50"), bd("40"), stock, ZERO)),
                rnd, decision);
    }

    /**
     * D79: what a unit in stock cost is what was paid for it. The shop floor's current cost is 40,
     * but the 100 units on the shelf were bought at 30 — weighted costing charges 30, replacement
     * costing charges today's 40. That difference is the whole reason a profitable-looking term can
     * end with cash and profit disagreeing.
     */
    @Test
    void weighted_costing_charges_the_price_the_stock_was_bought_at() {
        EnvironmentService.RoundEnvironment neutral =
                EnvironmentService.RoundEnvironment.neutral(1, java.util.Set.of("P"));
        DecisionPayload sell50 = new DecisionPayload(
                List.of(new DecisionPayload.ProductRow("P", ZERO)),
                List.of(new DecisionPayload.PriceRow("P", bd("100"))),
                List.of(),
                new DecisionPayload.ManpowerRow(10),
                List.of(), List.of(), ZERO, false);

        BigDecimal weightedCogs = engine.compute(scenarioWeighted(true), neutral,
                List.of(stockedTeam(sell50))).teams().get(0).cogs();
        BigDecimal replacementCogs = engine.compute(scenarioWeighted(false), neutral,
                List.of(stockedTeam(sell50))).teams().get(0).cogs();

        // nothing is produced, so every unit sold comes off the shelf: 100 x 30 actually paid for
        // them, versus 100 x 40 of "what it would cost to make one today".
        assertThat(weightedCogs).isEqualByComparingTo("3000");
        assertThat(replacementCogs).isEqualByComparingTo("4000");
    }

    private static Scenario scenarioWeighted(boolean weighted) {
        Scenario base = scenario();
        Scenario.Params p = base.params();
        Scenario.Params q = new Scenario.Params(p.rounds(), p.roundMinutes(), p.startingCash(),
                p.startingBrand(), p.fixedCostPerMonth(), p.brandDecay(), p.holdingCostPerUnit(),
                p.referenceQuality(), p.referenceSpend(), p.machineCostPerWorker(), p.hiringCostMonths(),
                p.severanceMonths(), p.minRndBenefitRatio(), p.baselineShare(), p.seasonality(),
                p.priceElasticity(), p.qualityElasticity(), p.brandElasticity(), p.marketingElasticity(),
                p.salaries(), p.startingStaff(), p.marketReportCost(), p.forecastHorizon(),
                p.forecastBand(), p.cycles(), p.cycleLength(), p.cycleDemandDrift(), p.cycleCostDrift(),
                p.qualityUpgrade(), p.qualityCostPerPoint(), p.autoWorkers(), p.guidedRounds(), weighted);
        return new Scenario(base.key(), q, base.products(), base.channels(), base.projects(),
                base.rounds(), base.segments(), base.events());
    }

    /** 100 units on the shelf that cost 30 each; today's unit cost is 40. */
    private static ScoringEngine.TeamInput stockedTeam(DecisionPayload decision) {
        return new ScoringEngine.TeamInput(1L, "ACTIVE", bd("1000000"), bd("50"), ZERO,
                Map.of("PRODUCTION", 10),
                Map.of("P", new ScoringEngine.ProductState("P", bd("100"), bd("50"), bd("40"),
                        bd("100"), bd("3000"))),
                active("PRJ"), decision);
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
                List.of(),
                ZERO, false);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
