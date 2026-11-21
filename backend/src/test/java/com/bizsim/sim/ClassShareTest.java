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
 * D115: the per-class breakdown the debrief shows beside the company share (D-C's option (i)).
 *
 * <p>The properties that make it safe to publish are tested here rather than left as intentions:
 *
 * <ol>
 *   <li>the awards add up — a company's class awards sum to the demand on its product line, and the
 *       companies' awards in one class sum to that class's served market;</li>
 *   <li>the percentages are of the class's own market, so they sum to ~100 across the class;</li>
 *   <li>a template that does not weight reach by class records <em>nothing</em> — there the market was one
 *       pool, and a per-class split would be a number the engine never produced.</li>
 * </ol>
 */
@SpringBootTest
class ClassShareTest extends PostgresTestBase {

    @Autowired
    ScenarioRepository scenarios;

    @Autowired
    ScoringEngine engine;

    @Test
    void the_awards_add_up_to_the_product_demand_and_to_the_class_market() {
        Scenario scenario = scenarios.load("standard-v1");
        assertThat(scenario.channels())
                .as("the shipped template states class reach (D111), so this is the weighted path")
                .anyMatch(Scenario.ChannelSpec::segmentSpecific);
        List<String> codes = scenario.products().stream().map(Scenario.ProductSpec::code).toList();
        EnvironmentService.RoundEnvironment env = EnvironmentService.RoundEnvironment.neutral(1, codes);
        List<ScoringEngine.TeamInput> teams = List.of(
                team(scenario, 1, Map.of("DIGITAL", new BigDecimal("25000000"))),
                team(scenario, 2, Map.of("TRADE", new BigDecimal("25000000"))),
                team(scenario, 3, Map.of("FIELD", new BigDecimal("25000000"))));

        ScoringEngine.RoundResult result = engine.compute(scenario, env, teams);
        List<String> segments = scenario.segments().stream().map(Scenario.SegmentSpec::code).toList();

        for (ScoringEngine.TeamOutcome outcome : result.teams()) {
            assertThat(outcome.classShares()).as("a weighted template records the breakdown").isNotEmpty();
            for (String product : codes) {
                BigDecimal demand = outcome.lines().stream()
                        .filter(line -> line.product().equals(product)).findFirst().orElseThrow().demand();
                BigDecimal awarded = outcome.classShares().stream()
                        .filter(share -> share.product().equals(product))
                        .map(ScoringEngine.ClassShare::demand)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(awarded).as("team %d %s: awards sum to the demand on the line",
                        outcome.teamId(), product).isEqualByComparingTo(demand);
                assertThat(outcome.classShares().stream()
                        .filter(share -> share.product().equals(product))
                        .map(ScoringEngine.ClassShare::segment).toList())
                        .as("every class of the template appears once for %s", product)
                        .containsExactlyElementsOf(segments);
            }
        }

        // One class at a time: the companies' awards are the class's whole served market, and the
        // percentages therefore add to 100 (up to the engine's 2-dp rounding).
        for (String product : codes) {
            for (String segment : segments) {
                List<ScoringEngine.ClassShare> cells = new ArrayList<>();
                for (ScoringEngine.TeamOutcome outcome : result.teams()) {
                    outcome.classShares().stream()
                            .filter(share -> share.product().equals(product)
                                    && share.segment().equals(segment))
                            .findFirst().ifPresent(cells::add);
                }
                assertThat(cells).hasSize(teams.size());
                BigDecimal market = cells.get(0).market();
                for (ScoringEngine.ClassShare cell : cells) {
                    assertThat(cell.market()).as("%s/%s is one market for every company", product, segment)
                            .isEqualByComparingTo(market);
                }
                BigDecimal handed = cells.stream().map(ScoringEngine.ClassShare::demand)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(handed).as("%s/%s: awards fill the class's market", product, segment)
                        .isEqualByComparingTo(market);
                BigDecimal percent = cells.stream().map(ScoringEngine.ClassShare::share)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(percent).as("%s/%s: shares add to 100", product, segment)
                        .isBetween(new BigDecimal("99.99"), new BigDecimal("100.01"));
            }
        }
    }

    /**
     * The point of segment weighting, stated as a test: the same spend in different channels wins
     * different classes on the same product. Two companies, one product, and their class shares are
     * ordered oppositely in mass and in premium.
     */
    @Test
    void two_companies_trade_places_between_the_classes_of_one_product() {
        Scenario scenario = scenarios.load("standard-v1");
        List<String> codes = scenario.products().stream().map(Scenario.ProductSpec::code).toList();
        EnvironmentService.RoundEnvironment env = EnvironmentService.RoundEnvironment.neutral(1, codes);
        List<ScoringEngine.TeamInput> teams = List.of(
                team(scenario, 1, Map.of("DIGITAL", new BigDecimal("30000000"))),
                team(scenario, 2, Map.of("FIELD", new BigDecimal("30000000"))));

        ScoringEngine.RoundResult result = engine.compute(scenario, env, teams);
        ScoringEngine.TeamOutcome digital = result.teams().get(0);
        ScoringEngine.TeamOutcome field = result.teams().get(1);

        // P1 is made for the mass market; both companies contest it, and Digital reaches mass hardest.
        BigDecimal digitalMass = shareOf(digital, "P1", "SEG-BASIC");
        BigDecimal fieldMass = shareOf(field, "P1", "SEG-BASIC");
        assertThat(digitalMass).as("Digital wins more of the mass class on the mass product")
                .isGreaterThan(fieldMass);

        // P3 is made for the affluent class; Field reaches it hardest, so the order reverses.
        BigDecimal digitalPremium = shareOf(digital, "P3", "SEG-PREMIUM");
        BigDecimal fieldPremium = shareOf(field, "P3", "SEG-PREMIUM");
        assertThat(fieldPremium).as("Field wins more of the affluent class on the premium product")
                .isGreaterThan(digitalPremium);
    }

    /** No class weights means one pool: the breakdown is empty, not a split of a market nobody split. */
    @Test
    void a_template_without_class_weights_records_no_breakdown() {
        Scenario base = scenarios.load("standard-v1");
        List<Scenario.ChannelSpec> plain = base.channels().stream()
                .map(channel -> new Scenario.ChannelSpec(channel.code(), channel.name(),
                        channel.reachWeight(), channel.brandAlpha()))
                .toList();
        Scenario scenario = new Scenario(base.key(), base.params(), base.products(), plain,
                base.projects(), base.rounds(), base.segments(), base.events());
        List<String> codes = scenario.products().stream().map(Scenario.ProductSpec::code).toList();
        EnvironmentService.RoundEnvironment env = EnvironmentService.RoundEnvironment.neutral(1, codes);
        List<ScoringEngine.TeamInput> teams = List.of(
                team(scenario, 1, Map.of("DIGITAL", new BigDecimal("20000000"))),
                team(scenario, 2, Map.of("TRADE", new BigDecimal("20000000"))));

        ScoringEngine.RoundResult result = engine.compute(scenario, env, teams);
        assertThat(result.teams()).allSatisfy(outcome ->
                assertThat(outcome.classShares()).as("no class weights, no breakdown").isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private static BigDecimal shareOf(ScoringEngine.TeamOutcome outcome, String product, String segment) {
        return outcome.classShares().stream()
                .filter(share -> share.product().equals(product) && share.segment().equals(segment))
                .findFirst().orElseThrow(
                        () -> new AssertionError("no award for " + product + "/" + segment))
                .share();
    }

    private ScoringEngine.TeamInput team(Scenario scenario, long id, Map<String, BigDecimal> spend) {
        Map<String, ScoringEngine.ProductState> products = new LinkedHashMap<>();
        for (Scenario.ProductSpec spec : scenario.products()) {
            products.put(spec.code(), new ScoringEngine.ProductState(spec.code(), spec.basePrice(),
                    spec.baseQuality(), spec.baseUnitCost(), BigDecimal.ZERO, BigDecimal.ZERO));
        }
        Map<String, Integer> staff = new LinkedHashMap<>();
        staff.put("PRODUCTION", 60);
        staff.put("SALES", 3);
        staff.put("RND", 2);
        staff.put("ADMIN", 2);
        List<DecisionPayload.ProductRow> production = new ArrayList<>();
        for (Scenario.ProductSpec spec : scenario.products()) {
            production.add(new DecisionPayload.ProductRow(spec.code(), BigDecimal.valueOf(300)));
        }
        List<DecisionPayload.ChannelRow> marketing = new ArrayList<>();
        for (Scenario.ChannelSpec channel : scenario.channels()) {
            marketing.add(new DecisionPayload.ChannelRow(channel.code(),
                    spend.getOrDefault(channel.code(), BigDecimal.ZERO)));
        }
        DecisionPayload decision = new DecisionPayload(production, List.of(), marketing, null,
                List.of(), List.of(), BigDecimal.ZERO, false);
        return new ScoringEngine.TeamInput(id, "ACTIVE", new BigDecimal("2000000000"),
                BigDecimal.ZERO, BigDecimal.ZERO, staff, products, Map.of(), decision);
    }
}
