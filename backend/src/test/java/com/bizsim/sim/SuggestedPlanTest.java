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
 * D129: a suggestion is a baseline, not a strategy.
 *
 * <p>The guided suggestion for a human seat used to be `profile("MID", "BALANCED")`. Before D128 that pair was
 * the neutral cell; after D128 the two-argument call resolves through {@code strategyFor("BALANCED")} and means
 * <strong>FOCUS</strong> — a suggestion that concentrated marketing on one class's channel and planned only that
 * class's product, for a company the guide knows nothing about. These tests pin the replacement: the whole line
 * planned, marketing pro rata to channel reach, price at the template's, and no money committed to quality, R&amp;D,
 * capacity or the market review.
 */
@SpringBootTest
class SuggestedPlanTest extends PostgresTestBase {

    @Autowired
    private ScenarioRepository scenarios;

    @Autowired
    private EnvironmentService environment;

    @Test
    void the_suggestion_is_neutral_and_commits_to_no_strategy() {
        Scenario scenario = scenarios.load("standard-v1");
        AutoWorker.Decision decision = decide(scenario, AutoWorker.neutral());
        DecisionPayload payload = decision.payload();

        // The whole line, not one class's product.
        assertThat(payload.production())
                .as("the suggestion plans every product the company carries")
                .hasSize(scenario.products().size());

        // Price starts from the template's, with no strategic posture applied. The policy still nudges it for
        // stock — an empty shelf facing unmet demand prices up 5% — which is a mechanic and not a strategy, so
        // the assertion allows that one step and nothing more.
        Map<String, BigDecimal> base = new LinkedHashMap<>();
        scenario.products().forEach(spec -> base.put(spec.code(), spec.basePrice()));
        payload.price().forEach(row -> {
            BigDecimal listed = base.get(row.product());
            BigDecimal rich = listed.multiply(new BigDecimal("1.05"));
            BigDecimal cheap = listed.multiply(new BigDecimal("0.96"));
            assertThat(row.unitPrice())
                    .as("%s is the template price, give or take the stock rule", row.product())
                    .isBetween(cheap.setScale(2, java.math.RoundingMode.HALF_UP),
                            rich.setScale(2, java.math.RoundingMode.HALF_UP));
        });

        // No strategic posture: the cost leader would undercut by 6% and the differentiator charge 6% more, so
        // sitting inside the stock-rule band is what proves strategy did not move the price.
        for (DecisionPayload.PriceRow row : payload.price()) {
            BigDecimal list = base.get(row.product());
            assertThat(row.unitPrice())
                    .as("%s is not a cost-leader or differentiator price", row.product())
                    .isGreaterThan(list.multiply(new BigDecimal("0.93")))
                    .isLessThan(list.multiply(new BigDecimal("1.08")));
        }

        // Marketing is pro rata to channel reach — the neutral split, not a focus bias.
        Map<String, BigDecimal> spend = new LinkedHashMap<>();
        payload.marketing().forEach(row -> spend.put(row.channel(), row.spend()));
        double total = spend.values().stream().mapToDouble(BigDecimal::doubleValue).sum();
        assertThat(total).as("the suggestion spends something on marketing").isPositive();
        double reachTotal = scenario.channels().stream()
                .mapToDouble(c -> c.reachWeight().doubleValue()).sum();
        for (Scenario.ChannelSpec channel : scenario.channels()) {
            double expected = channel.reachWeight().doubleValue() / reachTotal;
            double actual = spend.getOrDefault(channel.code(), BigDecimal.ZERO).doubleValue() / total;
            assertThat(actual)
                    .as("%s holds its reach share of the budget", channel.code())
                    .isCloseTo(expected, org.assertj.core.data.Offset.offset(0.03));
        }

        // Nothing committed that a trainee did not choose.
        assertThat(payload.quality()).as("no quality buy").isEmpty();
        assertThat(payload.rnd()).as("no R&D programme").isEmpty();
        assertThat(payload.capacityPurchase()).as("no capacity race").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(payload.marketReport()).as("no review purchase").isFalse();
    }

    @Test
    void the_neutral_profile_is_distinguishable_from_every_strategy() {
        // D129 kept the suggestion neutral because the pair it used before meant FOCUS. D130 retired personality
        // entirely, so the guard is now against the three strategies: none of them is what the suggestion uses.
        for (String strategy : List.of("COST", "DIFFERENTIATION", "COST_FOCUS", "DIFFERENTIATION_FOCUS")) {
            AutoWorker.Profile shipped = AutoWorker.profile("MID", strategy);
            assertThat(shipped.strategy()).isEqualTo(strategy);
            assertThat(shipped.pricePosture())
                    .as("%s moves the price away from neutral", strategy)
                    .isNotEqualByComparingTo(AutoWorker.neutral().pricePosture());
        }
        assertThat(AutoWorker.neutral().focused()).as("the suggestion narrows no line").isFalse();
        assertThat(AutoWorker.profile("MID", "COST_FOCUS").focused()).as("the focus strategy does").isTrue();
    }

    private AutoWorker.Decision decide(Scenario scenario, AutoWorker.Profile profile) {
        EnvironmentService.RoundEnvironment env = environment.forRound(scenario, QUIET_RUN, 1);
        AutoWorker.Context context = new AutoWorker.Context(1, scenario.params().rounds(),
                scenario.params().startingCash(), scenario.params().startingBrand(), BigDecimal.ZERO,
                Map.of("PRODUCTION", 30), Map.of(), Map.of(), List.of(), env);
        return AutoWorker.decide(scenario, 42L, 1L, context, profile);
    }

    private static final long QUIET_RUN = 987_654_320L;
}
