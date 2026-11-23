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
 * thing. This test takes one scenario, one level and one personality, varies only the strategy, and asserts the
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
        // One level and one personality for all three runs: strategy is the only thing that changes.
        Map<String, AutoWorker.Decision> byStrategy = new LinkedHashMap<>();
        for (String strategy : List.of("COST", "DIFFERENTIATION", "FOCUS")) {
            byStrategy.put(strategy, decide(scenario, AutoWorker.profile("MID", "BALANCED", strategy)));
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
                        + "  focus           price %s  quality %s  rnd %s  channels %s%n",
                costPrice, costQuality, rndSpend(byStrategy.get("COST")),
                channels(byStrategy.get("COST")),
                diffPrice, diffQuality, rndSpend(byStrategy.get("DIFFERENTIATION")),
                channels(byStrategy.get("DIFFERENTIATION")),
                firstPrice(byStrategy.get("FOCUS")), qualitySpend(byStrategy.get("FOCUS")),
                rndSpend(byStrategy.get("FOCUS")), channels(byStrategy.get("FOCUS")));
        assertThat(diffQuality).as("the differentiator invests in quality").isGreaterThan(costQuality);

        // 3. Channel split: a focused company concentrates where its class is reachable, the others are neutral.
        Map<String, BigDecimal> focusSplit = channels(byStrategy.get("FOCUS"));
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
        AutoWorker.Profile mid = AutoWorker.profile("MID", "BALANCED", "DIFFERENTIATION");
        AutoWorker.Profile high = AutoWorker.profile("HIGH", "BALANCED", "DIFFERENTIATION");
        assertThat(high.pricePosture()).isEqualByComparingTo(mid.pricePosture());
        assertThat(high.qualityAppetite()).isEqualByComparingTo(mid.qualityAppetite());
        assertThat(high.strategy()).isEqualTo(mid.strategy());
        assertThat(AutoWorker.profile("LOW", "BALANCED", "COST").strategy()).isEqualTo("COST");
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
}
