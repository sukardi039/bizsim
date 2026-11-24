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
 * D134: the four-cell measurement that decides whether quality needs cheaper input or higher value.
 *
 * <p>D133 shipped `quality-led-v1` with **two** knobs moved (elasticity 0.80 -> 1.30 *and* cost 60m -> 40m), so
 * the earlier comparison could not say which one did the work — and pricing one strategy's input lower inside a
 * single template is a subsidy, not an economic condition. The owner's objection is right, so this measures all
 * four combinations instead of arguing them:
 *
 * <pre>
 *                  elasticity 0.80        elasticity 1.30
 *   cost 60m       standard-v1 (known)    ?
 *   cost 40m       ?                      quality-led-v1 (known)
 * </pre>
 *
 * <p>Every cell plays the same contest: three strategies at HIGH level, two companies each, 8 rounds, against
 * each other. Reports average profit per round, so the ranking is comparable across cells.
 *
 * <p>Read-only: it builds a Scenario in memory and changes two values on it. No template is edited and no data
 * is written.
 */
@SpringBootTest
class QualityEconomicsTest extends PostgresTestBase {

    @Autowired
    private ScenarioRepository scenarios;

    @Autowired
    private EnvironmentService environment;

    @Test
    void measures_the_four_cells() {
        Scenario base = scenarios.load("standard-v1");
        System.out.printf("%nQUALITY ECONOMICS — HIGH level, 2 companies per strategy, 8 rounds%n");
        System.out.printf("%-14s %-14s %14s %14s %14s%n",
                "cost/point", "elasticity", "COST", "DIFFERENTIATION", "FOCUS");
        for (String cost : List.of("60000000", "40000000")) {
            for (String elasticity : List.of("0.80", "1.30")) {
                Scenario variant = withQuality(base, new BigDecimal(cost), new BigDecimal(elasticity));
                Measurement m = measure(variant);
                System.out.printf("%-14s %-14s %14s %14s %14s%n",
                        shortMoney(cost), elasticity,
                        m.profit("COST"), m.profit("DIFFERENTIATION"), m.profit("FOCUS"));
            }
        }
    }

    /** The same scenario with two values replaced — the only difference between the four cells. */
    private static Scenario withQuality(Scenario base, BigDecimal costPerPoint, BigDecimal elasticity) {
        Scenario.Params p = base.params();
        Scenario.Params changed = new Scenario.Params(p.rounds(), p.roundMinutes(), p.startingCash(),
                p.startingBrand(), p.fixedCostPerMonth(), p.brandDecay(), p.holdingCostPerUnit(),
                p.referenceQuality(), p.referenceSpend(), p.machineCostPerWorker(), p.hiringCostMonths(),
                p.severanceMonths(), p.minRndBenefitRatio(), p.baselineShare(), p.seasonality(),
                p.priceElasticity(), elasticity, p.brandElasticity(), p.marketingElasticity(),
                p.salaries(), p.startingStaff(), p.marketReportCost(), p.forecastHorizon(), p.forecastBand(),
                p.cycles(), p.cycleLength(), p.cycleDemandDrift(), p.cycleCostDrift(),
                p.qualityUpgrade(), costPerPoint, p.autoWorkers(), p.guidedRounds(), p.weightedCosting(),
                p.inertia());
        return new Scenario(base.key(), changed, base.products(), base.channels(), base.projects(),
                base.rounds(), base.segments(), base.events());
    }

    private static String shortMoney(String raw) {
        return new BigDecimal(raw).divide(new BigDecimal("1000000"), 0, java.math.RoundingMode.HALF_UP) + "m";
    }

    // ------------------------------------------------------------------ harness

    private Measurement measure(Scenario scenario) {
        List<Player> players = new ArrayList<>();
        for (String strategy : List.of("COST", "DIFFERENTIATION", "FOCUS")) {
            for (int i = 0; i < 2; i++) {
                players.add(new Player(scenario.params().startingCash(), scenario.params().startingBrand(),
                        AutoWorker.profile("HIGH", strategy)));
            }
        }
        Map<Player, List<ScoringEngine.ProductLine>> lastLines = new LinkedHashMap<>();
        for (int round = 1; round <= scenario.params().rounds(); round++) {
            EnvironmentService.RoundEnvironment env = environment.forRound(scenario, QUIET_RUN, round);
            List<ScoringEngine.TeamInput> inputs = new ArrayList<>();
            for (int i = 0; i < players.size(); i++) {
                Player player = players.get(i);
                AutoWorker.Context context = player.context(scenario, round, env,
                        lastLines.getOrDefault(player, List.of()));
                AutoWorker.Decision decision = AutoWorker.decide(scenario, 77L, i + 1L, context,
                        player.profile);
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

    private static final long QUIET_RUN = 987_654_318L;

    private record Measurement(List<Player> players) {
        String profit(String strategy) {
            List<Player> matching = players.stream()
                    .filter(p -> p.profile.strategy().equals(strategy)).toList();
            BigDecimal total = matching.stream()
                    .map(p -> p.cumulativeProfit.divide(BigDecimal.valueOf(Math.max(1, p.months)), 2,
                            java.math.RoundingMode.HALF_UP))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return total.divide(BigDecimal.valueOf(matching.size()), 0, java.math.RoundingMode.HALF_UP)
                    .toPlainString();
        }
    }

    private static final class Player {
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
                                   List<ScoringEngine.ProductLine> last) {
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
            Map<String, ScoringEngine.ProductState> next = new LinkedHashMap<>();
            for (ScoringEngine.ProductLine line : outcome.lines()) {
                next.put(line.product(), new ScoringEngine.ProductState(line.product(), line.price(),
                        line.quality(), line.unitCost(), line.closingStock(), line.stockValue()));
            }
            products = next;
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
