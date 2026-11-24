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

/**
 * D132: are the three strategies competitive with each other, or is one of them a losing hand?
 *
 * <p>Not an assertion-heavy test — a <strong>measurement</strong>. Six companies at the same level, two of each
 * strategy, played for a full run against each other, so the report says whether COST holds its own or leaks
 * margin. It is the diagnostic the owner asked for ("cost: how can we improve?"), and it is kept as a test so the
 * numbers are re-measured on every change rather than argued about from memory.
 *
 * <p>The pass condition is deliberately weak: <strong>every strategy must survive</strong> and no strategy may be
 * more than a factor of three behind the best. A strategy that always loses is not a strategy, it is a handicap;
 * the exact ranking is information, not a gate.
 */
@SpringBootTest
class StrategyCalibrationTest extends PostgresTestBase {

    @Autowired
    private ScenarioRepository scenarios;

    @Autowired
    private EnvironmentService environment;

    @Test
    void the_three_strategies_are_competitive_at_the_same_level() {
        Scenario scenario = scenarios.load("standard-v1");
        for (String level : List.of("HIGH", "MID")) {
            Measurement m = measure(scenario, level);
            System.out.printf("%nSTRATEGY CALIBRATION (%s level, %d rounds)%n", level, scenario.params().rounds());
            for (String strategy : List.of("COST", "DIFFERENTIATION", "FOCUS")) {
                System.out.printf("  %-16s avg profit/round %14s   cash %14s   brand %6s%n",
                        strategy, m.profit(strategy), m.cash(strategy), m.brand(strategy));
            }
            // What each strategy commits in its first month: the numbers behind the ranking.
            for (String strategy : List.of("COST", "DIFFERENTIATION", "FOCUS")) {
                DecisionPayload first = m.firstDecision(strategy);
                System.out.printf("     %-16s month 1: quality %12s  rnd %12s  marketing %12s  capacity %12s  review %s%n",
                        strategy, sum(first.quality(), DecisionPayload.QualityRow::invest),
                        sum(first.rnd(), DecisionPayload.RndRow::invest),
                        sum(first.marketing(), DecisionPayload.ChannelRow::spend),
                        first.capacityPurchase(), first.marketReport());
            }
            assertThatAllSurvive(level, m);
        }
    }

    private void assertThatAllSurvive(String level, Measurement m) {
        BigDecimal best = null;
        for (String strategy : List.of("COST", "DIFFERENTIATION", "FOCUS")) {
            BigDecimal profit = m.profit(strategy);
            if (best == null || profit.compareTo(best) > 0) {
                best = profit;
            }
        }
        for (String strategy : List.of("COST", "DIFFERENTIATION", "FOCUS")) {
            BigDecimal profit = m.profit(strategy);
            org.assertj.core.api.Assertions.assertThat(profit)
                    .as("%s at %s level must not be hopeless (best is %s)", strategy, level, best)
                    .isGreaterThan(best.multiply(new BigDecimal("-3")));
        }
    }

    private Measurement measure(Scenario scenario, String level) {
        List<Player> players = new ArrayList<>();
        for (String strategy : List.of("COST", "DIFFERENTIATION", "FOCUS")) {
            for (int i = 0; i < 2; i++) {
                players.add(new Player(scenario.params().startingCash(), scenario.params().startingBrand(),
                        AutoWorker.profile(level, strategy)));
            }
        }
        Map<Player, List<ScoringEngine.ProductLine>> lastLines = new LinkedHashMap<>();
        for (int round = 1; round <= scenario.params().rounds(); round++) {
            EnvironmentService.RoundEnvironment env = environment.forRound(scenario, QUIET_RUN, round);
            List<ScoringEngine.TeamInput> inputs = new ArrayList<>();
            for (int i = 0; i < players.size(); i++) {
                Player player = players.get(i);
                AutoWorker.Context context = player.context(scenario, round, env,
                        lastLines.getOrDefault(player, List.of()), i + 1L);
                AutoWorker.Decision decision = AutoWorker.decide(scenario, 99L, i + 1L, context,
                        player.profile);
                if (player.firstDecision == null) {
                    player.firstDecision = decision.payload();
                }
                inputs.add(player.input(decision.payload(), i + 1L));
            }
            ScoringEngine.RoundResult result = new ScoringEngine().compute(scenario, env, inputs);
            for (ScoringEngine.TeamOutcome outcome : result.teams()) {
                Player player = players.get((int) outcome.teamId() - 1);
                player.apply(outcome);
                lastLines.put(player, outcome.lines());
            }
        }
        return new Measurement(players);
    }

    private static final long QUIET_RUN = 987_654_319L;

    /** Per-strategy averages, which is the whole point of the exercise. */
    private record Measurement(List<Player> players) {
        BigDecimal profit(String strategy) {
            return average(strategy, p -> p.cumulativeProfit.divide(
                    BigDecimal.valueOf(Math.max(1, p.months)), 2, java.math.RoundingMode.HALF_UP));
        }

        BigDecimal cash(String strategy) {
            return average(strategy, p -> p.cash);
        }

        DecisionPayload firstDecision(String strategy) {
            return players.stream().filter(p -> p.profile.strategy().equals(strategy))
                    .findFirst().orElseThrow().firstDecision;
        }

        BigDecimal brand(String strategy) {
            return average(strategy, p -> p.brand);
        }

        private BigDecimal average(String strategy, java.util.function.Function<Player, BigDecimal> of) {
            List<Player> matching = players.stream()
                    .filter(p -> p.profile.strategy().equals(strategy)).toList();
            BigDecimal total = matching.stream().map(of).reduce(BigDecimal.ZERO, BigDecimal::add);
            return total.divide(BigDecimal.valueOf(matching.size()), 2, java.math.RoundingMode.HALF_UP);
        }
    }

    private static <T> BigDecimal sum(List<T> rows,
                                     java.util.function.Function<T, BigDecimal> of) {
        return rows.stream().map(of).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static final class Player {
        private DecisionPayload firstDecision;
        private final AutoWorker.Profile profile;
        private BigDecimal cash;
        private BigDecimal brand;
        private BigDecimal machineWorkers = BigDecimal.ZERO;
        private Map<String, Integer> staff = new LinkedHashMap<>();
        private Map<String, ScoringEngine.ProductState> products = new LinkedHashMap<>();
        private Map<String, ScoringEngine.RndState> rnd = new LinkedHashMap<>();
        private String status = "ACTIVE";
        private BigDecimal cumulativeProfit = BigDecimal.ZERO;
        private int months = 0;

        Player(BigDecimal cash, BigDecimal brand, AutoWorker.Profile profile) {
            this.cash = cash;
            this.brand = brand;
            this.profile = profile;
        }

        AutoWorker.Context context(Scenario scenario, int round, EnvironmentService.RoundEnvironment env,
                                   List<ScoringEngine.ProductLine> last, long teamId) {
            Map<String, ScoringEngine.ProductState> state = new LinkedHashMap<>();
            for (Scenario.ProductSpec spec : scenario.products()) {
                ScoringEngine.ProductState previous = products.get(spec.code());
                state.put(spec.code(), previous != null ? previous
                        : new ScoringEngine.ProductState(spec.code(), spec.basePrice(), spec.baseQuality(),
                        spec.baseUnitCost(), BigDecimal.ZERO, BigDecimal.ZERO));
            }
            return new AutoWorker.Context(round, scenario.params().rounds(), cash, brand, machineWorkers,
                    staff, state, rnd, last, env);
        }

        ScoringEngine.TeamInput input(DecisionPayload payload, long teamId) {
            return new ScoringEngine.TeamInput(teamId, status, cash, brand, machineWorkers, staff,
                    products, rnd, payload);
        }

        void apply(ScoringEngine.TeamOutcome outcome) {
            cash = outcome.cash();
            brand = outcome.brand();
            machineWorkers = outcome.machineWorkers();
            staff = outcome.staff();
            status = outcome.status();
            cumulativeProfit = cumulativeProfit.add(outcome.profit() == null
                    ? BigDecimal.ZERO : outcome.profit());
            months++;
            // The engine hands the next month's product and project state back through the lines and updates.
            Map<String, ScoringEngine.ProductState> nextProducts = new LinkedHashMap<>();
            for (ScoringEngine.ProductLine line : outcome.lines()) {
                nextProducts.put(line.product(), new ScoringEngine.ProductState(line.product(), line.price(),
                        line.quality(), line.unitCost(), line.closingStock(), line.stockValue()));
            }
            products = nextProducts;
            Map<String, ScoringEngine.RndState> nextRnd = new LinkedHashMap<>();
            for (ScoringEngine.RndUpdate update : outcome.rndUpdates()) {
                nextRnd.put(update.project(), new ScoringEngine.RndState(update.project(),
                        update.invested(), update.monthsInvested(), update.startedRound(),
                        update.status(), update.benefitFactor()));
            }
            rnd = nextRnd;
        }
    }
}
