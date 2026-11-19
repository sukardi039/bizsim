package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D104: units are whole things.
 *
 * <p>A live class showed stock of **48.67** and **73.85** in the production screen. The engine scaled
 * production by a fraction whenever the workforce — or the cash — fell short of the plan, and rounded the
 * result to two decimals. Stock, demand and production are physical: a company can hold 48 of a product
 * or 49, make 13 or 14, and never two thirds of one. The fractional piece was an artefact of a rounding
 * decision, not of the model.
 *
 * <p>These tests drive the engine directly: the defect is arithmetic, so it is proved where it happens
 * rather than through a screen.
 */
@SpringBootTest
class WholeUnitsTest extends PostgresTestBase {

    @Autowired
    ScenarioRepository scenarios;

    @Autowired
    ScoringEngine engine;

    /**
     * A plan that needs far more workers than it has. Every product's output must come back whole, and the
     * rounding must never invent a unit for a company that is short of capacity.
     */
    @Test
    void a_short_workforce_produces_whole_units_not_fractions() {
        Scenario scenario = scenarios.load("standard-v1");
        // 30 workers against a plan of ~150 worker-equivalents: a factor near 0.2 — the shape that left
        // 48.67 and 73.85 in the classroom.
        Map<String, BigDecimal> plan = new LinkedHashMap<>();
        plan.put("P1", BigDecimal.valueOf(1500));
        plan.put("P2", BigDecimal.valueOf(300));
        plan.put("P3", BigDecimal.valueOf(120));

        ScoringEngine.RoundResult result = engine.compute(scenario, EnvironmentService.RoundEnvironment
                .neutral(1, scenario.products().stream().map(Scenario.ProductSpec::code).toList()),
                List.of(team(scenario, 1, plan, 30, new BigDecimal("2000000000"))));

        for (ScoringEngine.ProductLine line : result.teams().get(0).lines()) {
            assertWhole(line);
            assertThat(line.produced())
                    .as("%s: a cut can only cost output, never invent it", line.product())
                    .isLessThanOrEqualTo(line.planned());
        }
    }

    /** The cash cap is the other place a fraction used to appear; the same rule has to hold there. */
    @Test
    void a_company_that_cannot_pay_for_its_materials_produces_whole_units() {
        Scenario scenario = scenarios.load("standard-v1");
        Map<String, BigDecimal> plan = new LinkedHashMap<>();
        plan.put("P1", BigDecimal.valueOf(900));
        plan.put("P2", BigDecimal.valueOf(120));
        plan.put("P3", BigDecimal.valueOf(60));

        // Enough workers for the whole order, far too little cash: only the cash cap binds.
        ScoringEngine.RoundResult result = engine.compute(scenario, EnvironmentService.RoundEnvironment
                .neutral(1, scenario.products().stream().map(Scenario.ProductSpec::code).toList()),
                List.of(team(scenario, 1, plan, 200, new BigDecimal("300000000"))));

        for (ScoringEngine.ProductLine line : result.teams().get(0).lines()) {
            assertWhole(line);
        }
    }

    /** A plan that fits its workforce is untouched: this fix must not shave a unit off a normal month. */
    @Test
    void a_plan_that_fits_its_workforce_produces_exactly_what_was_planned() {
        Scenario scenario = scenarios.load("standard-v1");
        Map<String, BigDecimal> plan = new LinkedHashMap<>();
        plan.put("P1", BigDecimal.valueOf(120));
        plan.put("P2", BigDecimal.valueOf(30));
        plan.put("P3", BigDecimal.valueOf(12));

        ScoringEngine.RoundResult result = engine.compute(scenario, EnvironmentService.RoundEnvironment
                .neutral(1, scenario.products().stream().map(Scenario.ProductSpec::code).toList()),
                List.of(team(scenario, 1, plan, 60, new BigDecimal("2000000000"))));

        for (ScoringEngine.ProductLine line : result.teams().get(0).lines()) {
            assertThat(line.produced())
                    .as("%s produced the plan unchanged", line.product())
                    .isEqualByComparingTo(plan.get(line.product()));
            assertWhole(line);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void assertWhole(ScoringEngine.ProductLine line) {
        for (Map.Entry<String, BigDecimal> field : Map.of(
                "produced", line.produced(),
                "closing stock", line.closingStock(),
                "sold", line.sold(),
                "demand", line.demand()).entrySet()) {
            assertThat(field.getValue().stripTrailingZeros().scale())
                    .as("%s %s is %s — units are whole pieces (D104)",
                            line.product(), field.getKey(), field.getValue())
                    .isLessThanOrEqualTo(0);
        }
    }

    private ScoringEngine.TeamInput team(Scenario scenario, long id, Map<String, BigDecimal> plan,
                                         int productionStaff, BigDecimal cash) {
        Map<String, ScoringEngine.ProductState> products = new LinkedHashMap<>();
        for (Scenario.ProductSpec spec : scenario.products()) {
            products.put(spec.code(), new ScoringEngine.ProductState(spec.code(),
                    spec.basePrice(), spec.baseQuality(), spec.baseUnitCost(),
                    BigDecimal.ZERO, BigDecimal.ZERO));
        }
        Map<String, Integer> staff = new LinkedHashMap<>();
        staff.put("PRODUCTION", productionStaff);
        staff.put("SALES", 3);
        staff.put("RND", 2);
        staff.put("ADMIN", 2);

        List<DecisionPayload.ProductRow> production = new ArrayList<>();
        plan.forEach((code, units) -> production.add(new DecisionPayload.ProductRow(code, units)));
        DecisionPayload decision = new DecisionPayload(production, List.of(), List.of(), null,
                List.of(), List.of(), BigDecimal.ZERO, false);

        return new ScoringEngine.TeamInput(id, "ACTIVE", cash, BigDecimal.ZERO, BigDecimal.ZERO,
                staff, products, Map.of(), decision);
    }
}
