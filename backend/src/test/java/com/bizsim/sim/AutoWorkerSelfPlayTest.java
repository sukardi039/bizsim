package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Self-play calibration (D50): does a level <em>mean</em> anything?
 *
 * <p>Six companies play the shipped template for a whole run with nobody at the keyboard — two of each
 * level — and the test asserts what the product promises: the better level earns more, the worse level
 * earns less, and a replayed run produces the identical numbers. This is also the economy's calibration
 * harness: if the ordering inverts, either the policy or the template is wrong, and both are visible
 * here before a class ever meets it.
 *
 * <p>It drives {@link AutoWorker} and {@link ScoringEngine} directly — no HTTP, no clock — so it runs
 * in seconds and describes the game rather than the plumbing.
 */
@SpringBootTest
class AutoWorkerSelfPlayTest extends PostgresTestBase {

    @Autowired
    private ScenarioRepository scenarios;

    @Test
    void a_better_level_earns_more_over_a_full_run() {
        Scenario scenario = scenarios.load("standard-v1");
        assertThat(scenario.params().rounds()).isGreaterThanOrEqualTo(4);

        Game first = play(scenario, 7L);
        Game second = play(scenario, 7L);

        // Determinism: the same seed is the same history, decision for decision.
        assertThat(second.signature()).isEqualTo(first.signature());

        BigDecimal high = first.averageProfit("HIGH");
        BigDecimal mid = first.averageProfit("MID");
        BigDecimal low = first.averageProfit("LOW");

        assertThat(high)
                .as("high %s vs mid %s", high, mid)
                .isGreaterThan(mid);
        assertThat(mid)
                .as("mid %s vs low %s", mid, low)
                .isGreaterThan(low);
        // The calibration table: the numbers this test asserts on, printed so a template change can
        // be judged by them instead of by a pass/fail alone.
        System.out.printf("%nSELF-PLAY (run of %d rounds): high %.0f | mid %.0f | low %.0f  (avg profit/round)%n",
                scenario.params().rounds(), high, mid, low);

        // Every level has to survive: a class should not watch a bot die of starvation in round 2.
        assertThat(first.insolvent()).isEmpty();
    }

    // ------------------------------------------------------------------ the harness

    /** Six companies, two per level, played to the end of the run. */
    private Game play(Scenario scenario, long seed) {
        List<Player> players = new ArrayList<>();
        for (String level : List.of("HIGH", "MID", "LOW")) {
            for (int i = 0; i < 2; i++) {
                long teamId = seed * 100 + players.size();
                players.add(new Player(teamId, level, AutoWorker.profile(level, "BALANCED"),
                        scenario.params().startingCash(), scenario.params().startingBrand()));
            }
        }

        Map<Long, List<ScoringEngine.ProductLine>> lastLines = new LinkedHashMap<>();
        for (int round = 1; round <= scenario.params().rounds(); round++) {
            EnvironmentService.RoundEnvironment environment = neutral(scenario, round);
            List<ScoringEngine.TeamInput> inputs = new ArrayList<>();
            for (Player player : players) {
                AutoWorker.Context context = player.context(scenario, round, environment,
                        lastLines.getOrDefault(player.teamId, List.of()));
                AutoWorker.Decision decision = AutoWorker.decide(scenario, seed, player.teamId,
                        context, player.profile);
                inputs.add(player.input(decision.payload()));
            }
            ScoringEngine.RoundResult result = new ScoringEngine().compute(scenario, environment, inputs);
            for (ScoringEngine.TeamOutcome outcome : result.teams()) {
                Player player = players.stream()
                        .filter(p -> p.teamId == outcome.teamId()).findFirst().orElseThrow();
                player.apply(outcome);
                lastLines.put(player.teamId, outcome.lines());
            }
        }
        return new Game(players);
    }

    private static EnvironmentService.RoundEnvironment neutral(Scenario scenario, int round) {
        Map<String, BigDecimal> multipliers = new LinkedHashMap<>();
        for (Scenario.ProductSpec product : scenario.products()) {
            multipliers.put(product.code(), scenario.seasonality(round));
        }
        return new EnvironmentService.RoundEnvironment(round, scenario.seasonality(round),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "Stable", multipliers, List.of());
    }

    /** One company's mutable state, exactly what the engine hands back each month. */
    private static final class Player {
        private final long teamId;
        private final String level;
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

        Player(long teamId, String level, AutoWorker.Profile profile, BigDecimal cash, BigDecimal brand) {
            this.teamId = teamId;
            this.level = level;
            this.profile = profile;
            this.cash = cash;
            this.brand = brand;
        }

        AutoWorker.Context context(Scenario scenario, int round,
                                   EnvironmentService.RoundEnvironment environment,
                                   List<ScoringEngine.ProductLine> last) {
            Map<String, ScoringEngine.ProductState> state = new LinkedHashMap<>();
            for (Scenario.ProductSpec spec : scenario.products()) {
                ScoringEngine.ProductState carried = products.get(spec.code());
                state.put(spec.code(), new ScoringEngine.ProductState(spec.code(),
                        carried == null ? spec.basePrice() : carried.price(),
                        carried == null ? spec.baseQuality() : carried.quality(),
                        carried == null ? spec.baseUnitCost() : carried.unitCost(),
                        carried == null ? BigDecimal.ZERO : carried.stock()));
            }
            Map<String, Integer> workforce = staff.isEmpty()
                    ? Map.of("PRODUCTION", scenario.params().startingStaff()
                    .getOrDefault("PRODUCTION", 10)) : staff;
            return new AutoWorker.Context(round, scenario.params().rounds(), cash, brand,
                    machineWorkers, workforce, state, rnd, last, environment);
        }

        ScoringEngine.TeamInput input(DecisionPayload payload) {
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
            Map<String, ScoringEngine.ProductState> next = new LinkedHashMap<>();
            for (ScoringEngine.ProductLine line : outcome.lines()) {
                next.put(line.product(), new ScoringEngine.ProductState(line.product(), line.price(),
                        line.quality(), line.unitCost(), line.closingStock()));
            }
            products = next;
            Map<String, ScoringEngine.RndState> rndNext = new LinkedHashMap<>();
            for (ScoringEngine.RndUpdate update : outcome.rndUpdates()) {
                rndNext.put(update.project(), new ScoringEngine.RndState(update.project(),
                        update.invested(), update.monthsInvested(), update.startedRound(),
                        update.status(), update.benefitFactor()));
            }
            rnd = rndNext;
        }

        boolean insolvent() {
            return "INSOLVENT".equals(status);
        }

        BigDecimal average() {
            return months == 0 ? BigDecimal.ZERO
                    : cumulativeProfit.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        }
    }

    private record Game(List<Player> players) {
        /** Every company's level and final cumulative profit: equal runs produce equal strings. */
        List<String> signature() {
            return players.stream()
                    .map(p -> p.level + ":" + p.cumulativeProfit.setScale(2, RoundingMode.HALF_UP))
                    .toList();
        }

        BigDecimal averageProfit(String level) {
            return players.stream().filter(p -> p.level.equals(level))
                    .map(Player::average).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        }

        List<String> insolvent() {
            return players.stream().filter(Player::insolvent).map(p -> p.level + "#" + p.teamId).toList();
        }
    }
}
