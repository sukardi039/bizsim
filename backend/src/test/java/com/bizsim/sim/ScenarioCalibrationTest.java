package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the shipped scenario data, not the engine: three plausible strategies must produce three
 * different outcomes, and the discount strategy must not automatically win (D14). The printed table is
 * the one copied into {@code docs/SCENARIO_CALIBRATION.md}, so the doc cannot drift from the code.
 *
 * <p>All constants here are provisional (D16) — this test says "the shape of the lesson holds", not
 * "these are the final numbers".
 */
@SpringBootTest
class ScenarioCalibrationTest extends PostgresTestBase {

    @Autowired
    private ScenarioRepository scenarios;
    @Autowired
    private ScoringEngine engine;

    @Test
    void three_strategies_produce_three_outcomes_and_discounting_does_not_win_by_itself() {
        Scenario scenario = scenarios.load("standard-v1");

        // A: premium pricing, conservative volume.
        ScoringEngine.TeamInput a = team(scenario, 1,
                decision(new int[]{500, 150, 40}, new long[]{1_050_000, 2_600_000, 5_200_000},
                        Map.of("DIGITAL", 20_000_000L, "TRADE", 10_000_000L), 30, 0));
        // B: 10% undercut on everything, heavy trade spend.
        ScoringEngine.TeamInput b = team(scenario, 2,
                decision(new int[]{600, 150, 30}, new long[]{900_000, 2_250_000, 4_500_000},
                        Map.of("TRADE", 30_000_000L), 30, 0));
        // C: base price, full capacity, balanced channels, one R&D programme.
        ScoringEngine.TeamInput c = team(scenario, 3,
                decision(new int[]{600, 150, 30}, new long[]{1_000_000, 2_500_000, 5_000_000},
                        Map.of("DIGITAL", 15_000_000L, "TRADE", 10_000_000L, "FIELD", 5_000_000L),
                        30, 30_000_000L));

        EnvironmentService.RoundEnvironment neutral =
                EnvironmentService.RoundEnvironment.neutral(1, java.util.Set.of("P1", "P2", "P3"));
        List<ScoringEngine.TeamOutcome> outcomes = engine.compute(scenario, neutral, List.of(a, b, c)).teams();
        BigDecimal pool = engine.marketPool(scenario, neutral, 3);

        System.out.println("| company | revenue | profit | share % | cash | brand | sold | lost | util % |");
        for (ScoringEngine.TeamOutcome o : outcomes) {
            BigDecimal sold = o.lines().stream().map(ScoringEngine.ProductLine::sold)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            System.out.printf("| %d | %s | %s | %s | %s | %s | %s | %s | %s |%n", o.teamId(),
                    o.revenue().toPlainString(), o.profit().toPlainString(),
                    percent(sold, pool), o.cash().toPlainString(), o.brand().toPlainString(),
                    sold.toPlainString(), o.lostSales().toPlainString(),
                    percent(o.capacityUsed(), o.capacityAvailable()));
        }

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes).allMatch(o -> "ACTIVE".equals(o.status()));
        // The lesson (D14): volume can be bought with a discount, profit cannot. B sells more units
        // than A and still earns less.
        BigDecimal unitsA = outcomes.get(0).lines().stream()
                .map(ScoringEngine.ProductLine::sold).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unitsB = outcomes.get(1).lines().stream()
                .map(ScoringEngine.ProductLine::sold).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(unitsB).isGreaterThan(unitsA);
        assertThat(outcomes.get(1).profit()).isLessThan(outcomes.get(0).profit());
    }

    // ------------------------------------------------------------------ fixtures

    private static String percent(BigDecimal numerator, BigDecimal denominator) {
        return denominator.signum() == 0 ? "0.00"
                : numerator.multiply(new BigDecimal("100"))
                .divide(denominator, 2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static ScoringEngine.TeamInput team(Scenario scenario, long id, DecisionPayload decision) {
        Map<String, ScoringEngine.ProductState> products = new LinkedHashMap<>();
        for (Scenario.ProductSpec p : scenario.products()) {
            products.put(p.code(), new ScoringEngine.ProductState(p.code(), p.basePrice(),
                    p.baseQuality(), p.baseUnitCost(), BigDecimal.ZERO));
        }
        Map<String, ScoringEngine.RndState> rnd = new LinkedHashMap<>();
        for (Scenario.ProjectSpec project : scenario.projects()) {
            rnd.put(project.code(), new ScoringEngine.RndState(project.code(), BigDecimal.ZERO, 0,
                    null, "ACTIVE", BigDecimal.ZERO));
        }
        return new ScoringEngine.TeamInput(id, "ACTIVE", scenario.params().startingCash(),
                scenario.params().startingBrand(), BigDecimal.ZERO, scenario.params().startingStaff(),
                products, rnd, decision);
    }

    private static DecisionPayload decision(int[] units, long[] prices, Map<String, Long> marketing,
                                            int production, long rnd) {
        List<DecisionPayload.ProductRow> productionRows = List.of(
                new DecisionPayload.ProductRow("P1", BigDecimal.valueOf(units[0])),
                new DecisionPayload.ProductRow("P2", BigDecimal.valueOf(units[1])),
                new DecisionPayload.ProductRow("P3", BigDecimal.valueOf(units[2])));
        List<DecisionPayload.PriceRow> priceRows = List.of(
                new DecisionPayload.PriceRow("P1", BigDecimal.valueOf(prices[0])),
                new DecisionPayload.PriceRow("P2", BigDecimal.valueOf(prices[1])),
                new DecisionPayload.PriceRow("P3", BigDecimal.valueOf(prices[2])));
        List<DecisionPayload.ChannelRow> channelRows = marketing.entrySet().stream()
                .map(e -> new DecisionPayload.ChannelRow(e.getKey(), BigDecimal.valueOf(e.getValue())))
                .toList();
        return new DecisionPayload(productionRows, priceRows, channelRows,
                new DecisionPayload.ManpowerRow(production),
                List.of(new DecisionPayload.RndRow("RND-P1-COST", BigDecimal.valueOf(rnd))),
                BigDecimal.ZERO, false);
    }
}
