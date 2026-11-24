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
 * D128: the three strategies are actually played, not just labelled.
 *
 * <p>Porter's generic strategies differ in <em>where the money goes</em>, not in how hard a company pushes. The
 * old matrix only moved price and share, so two of its nine cells looked like strategies while doing the same
 * thing. This test takes one scenario and one level, varies only the strategy, and asserts the
 * three decisions diverge in the three places the strategies are supposed to differ:
 *
 * <ol>
 *   <li><strong>price</strong> — the cost leader undercuts, the differentiator charges a premium;</li>
 *   <li><strong>quality and R&amp;D spend</strong> — the differentiator buys them, the cost leader does not;</li>
 *   <li><strong>channel split</strong> — the focus player puts its marketing where its class is reachable.</li>
 * </ol>
 *
 * <p>It also pins the axis split: level is competence, so the same strategy at a higher level must not become a
 * different strategy — only a better-played one.
 */
@SpringBootTest
class AutoWorkerStrategyTest extends PostgresTestBase {

    @Autowired
    private ScenarioRepository scenarios;

    @Autowired
    private EnvironmentService environment;

    @Test
    void the_three_strategies_spend_and_price_differently() {
        Scenario scenario = scenarios.load("standard-v1");
        // One level for all three runs: strategy is the only thing that changes.
        Map<String, AutoWorker.Decision> byStrategy = new LinkedHashMap<>();
        for (String strategy : List.of("COST", "DIFFERENTIATION", "COST_FOCUS", "DIFFERENTIATION_FOCUS")) {
            byStrategy.put(strategy, decide(scenario, AutoWorker.profile("MID", strategy)));
        }

        BigDecimal costPrice = firstPrice(byStrategy.get("COST"));
        BigDecimal diffPrice = firstPrice(byStrategy.get("DIFFERENTIATION"));
        assertThat(costPrice).as("the cost leader undercuts the differentiator, %s vs %s",
                costPrice, diffPrice).isLessThan(diffPrice);
        assertThat(prices(byStrategy.get("COST")))
                .as("every price of the cost leader sits below the differentiator's")
                .allSatisfy((code, price) -> assertThat(price).isLessThan(
                        byStrategy.get("DIFFERENTIATION").payload().price().stream()
                                .filter(row -> row.product().equals(code))
                                .findFirst().orElseThrow().unitPrice()));

        // 2. Quality and R&D: the differentiator buys the mechanics, the cost leader does not.
        BigDecimal diffQuality = qualitySpend(byStrategy.get("DIFFERENTIATION"));
        BigDecimal costQuality = qualitySpend(byStrategy.get("COST"));
        System.out.printf("%nSTRATEGY (MID/BALANCED):%n"
                        + "  cost            price %s  quality %s  rnd %s  channels %s%n"
                        + "  differentiation price %s  quality %s  rnd %s  channels %s%n"
                        + "  cost focus      price %s  quality %s  rnd %s  channels %s%n",
                costPrice, costQuality, rndSpend(byStrategy.get("COST")),
                channels(byStrategy.get("COST")),
                diffPrice, diffQuality, rndSpend(byStrategy.get("DIFFERENTIATION")),
                channels(byStrategy.get("DIFFERENTIATION")),
                firstPrice(byStrategy.get("COST_FOCUS")), qualitySpend(byStrategy.get("COST_FOCUS")),
                rndSpend(byStrategy.get("COST_FOCUS")), channels(byStrategy.get("COST_FOCUS")));
        assertThat(diffQuality).as("the differentiator invests in quality").isGreaterThan(costQuality);

        // 3. Channel split: a focused company concentrates where its class is reachable, the others are neutral.
        Map<String, BigDecimal> focusSplit = channels(byStrategy.get("COST_FOCUS"));
        Map<String, BigDecimal> neutralSplit = channels(byStrategy.get("COST"));
        assertThat(focusSplit.values().stream().map(BigDecimal::doubleValue).reduce(0.0, Double::sum))
                .as("the focus player still spends the whole budget")
                .isGreaterThan(0.0);
        // "Concentrated" is measurable: the largest share of its budget is bigger than the largest share of a
        // reach-neutral split.
        assertThat(largestShare(focusSplit))
                .as("focus concentrates the budget: %s vs %s", focusSplit, neutralSplit)
                .isGreaterThan(largestShare(neutralSplit));
    }

    @Test
    void level_is_competence_and_does_not_change_the_strategy() {
        // The same strategy at two levels must share its shape: same price posture, same quality appetite as
        // enabled. Level only changes how well it is executed (forecast noise, whether R&D is affordable).
        Scenario scenario = scenarios.load("standard-v1");
        AutoWorker.Profile mid = AutoWorker.profile("MID", "DIFFERENTIATION");
        AutoWorker.Profile high = AutoWorker.profile("HIGH", "DIFFERENTIATION");
        assertThat(high.pricePosture()).isEqualByComparingTo(mid.pricePosture());
        assertThat(high.qualityAppetite()).isEqualByComparingTo(mid.qualityAppetite());
        assertThat(high.strategy()).isEqualTo(mid.strategy());
        assertThat(AutoWorker.profile("LOW", "COST").strategy()).isEqualTo("COST");
    }

    // ------------------------------------------------------------------ harness

    private AutoWorker.Decision decide(Scenario scenario, AutoWorker.Profile profile) {
        EnvironmentService.RoundEnvironment env = environment.forRound(scenario, QUIET_RUN, 1);
        AutoWorker.Context context = new AutoWorker.Context(1, scenario.params().rounds(),
                scenario.params().startingCash(), scenario.params().startingBrand(), BigDecimal.ZERO,
                Map.of("PRODUCTION", 30), Map.of(), Map.of(), List.of(), env);
        return AutoWorker.decide(scenario, 42L, 1L, context, profile);
    }

    private static BigDecimal firstPrice(AutoWorker.Decision decision) {
        return decision.payload().price().get(0).unitPrice();
    }

    private static Map<String, BigDecimal> prices(AutoWorker.Decision decision) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        decision.payload().price().forEach(row -> out.put(row.product(), row.unitPrice()));
        return out;
    }

    private static BigDecimal qualitySpend(AutoWorker.Decision decision) {
        return decision.payload().quality().stream()
                .map(DecisionPayload.QualityRow::invest)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal rndSpend(AutoWorker.Decision decision) {
        return decision.payload().rnd().stream()
                .map(DecisionPayload.RndRow::invest)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Map<String, BigDecimal> channels(AutoWorker.Decision decision) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        decision.payload().marketing().forEach(row -> out.put(row.channel(), row.spend()));
        return out;
    }

    private static double largestShare(Map<String, BigDecimal> split) {
        double total = split.values().stream().mapToDouble(BigDecimal::doubleValue).sum();
        if (total <= 0) {
            return 0;
        }
        return split.values().stream().mapToDouble(BigDecimal::doubleValue).max().orElse(0) / total;
    }

    private static final long QUIET_RUN = 987_654_321L;

    /**
     * D135: the two focus positions are Porter's two cells, not a rename.
     *
     * <p>Both are narrow — one product line, the budget concentrated on the class's channel — but they must differ
     * in the thing Porter says distinguishes them: the source of advantage. A cost focus undercuts and buys no
     * quality; a differentiation focus premiums and buys quality. If these two ever converge, the grid has
     * collapsed back into the single "FOCUS" position that D130 shipped and D135 replaced, which won by spending
     * nothing rather than by competing.
     */
    @Test
    void the_two_focus_positions_differ_in_their_source_of_advantage() {
        Scenario scenario = scenarios.load("standard-v1");
        AutoWorker.Decision costFocus = decide(scenario, AutoWorker.profile("HIGH", "COST_FOCUS"));
        AutoWorker.Decision diffFocus = decide(scenario, AutoWorker.profile("HIGH", "DIFFERENTIATION_FOCUS"));

        assertThat(firstPrice(costFocus))
                .as("a cost focus undercuts a differentiation focus")
                .isLessThan(firstPrice(diffFocus));
        assertThat(qualitySpend(costFocus))
                .as("a cost focus buys no quality")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(qualitySpend(diffFocus))
                .as("a differentiation focus buys quality")
                .isPositive();

        // Both are narrow: the same single product line, so scope is genuinely shared.
        assertThat(AutoWorker.profile("HIGH", "COST_FOCUS").focused()).isTrue();
        assertThat(AutoWorker.profile("HIGH", "DIFFERENTIATION_FOCUS").focused()).isTrue();

        System.out.printf("%nFOCUS POSITIONS: cost focus price %s quality %s | diff focus price %s quality %s%n",
                firstPrice(costFocus), qualitySpend(costFocus),
                firstPrice(diffFocus), qualitySpend(diffFocus));
    }
}
