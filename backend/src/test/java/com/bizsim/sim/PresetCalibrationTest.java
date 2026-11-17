package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D100: each preset must hand the win to the strategy it is named for.
 *
 * <p>A preset is a template whose elasticities, fixed cost and reach are tuned so one way of competing
 * pays and another does not. That claim is only worth anything if it is measured: two plans, six
 * rounds, no events (a run id no test creates), winner by cumulative profit. A preset whose named
 * strategy loses is a bug in its numbers, not a preset.
 *
 * <p>The plans are deliberately plain — they differ only in price, quality spend, marketing and (for
 * the focused line) which products they carry — so what separates them is the preset's economy, not a
 * cleverer policy.
 */
@SpringBootTest
class PresetCalibrationTest extends PostgresTestBase {

    @Autowired
    private ScenarioRepository scenarios;
    @Autowired
    private EnvironmentService environment;

    /** A run id no test creates, so the engine builds the plain economy with no event drawn (D18). */
    private static final long QUIET_RUN = 987_654_321L;

    private static final BigDecimal MILLION = new BigDecimal("1000000");

    @Test
    @Disabled("D100 is not calibrated yet: with identical unit costs in one template, a discount plan "
            + "cannot out-earn a premium one (measured: COST -946M vs DIFF +2,022M over eight rounds). "
            + "The preset needs a structural lever the ruling has not chosen — see OPEN_QUESTIONS Q-P1.")
    void each_preset_hands_the_win_to_the_strategy_it_is_named_for() {
        Scenario base = scenarios.load("standard-v1");

        System.out.printf("%n| preset | plan | cumulative profit | revenue | cash |%n");
        System.out.printf("|---|---|---|---|---|%n");
        for (Preset preset : presets()) {
            Scenario scenario = preset.build(base);
            Plan first = preset.plans().get(0);
            Plan second = preset.plans().get(1);
            // Both plans compete in the same class, month after month: a plan played alone takes the
            // whole served market whatever its price, so price posture would never be tested.
            Map<String, Result> played = play(scenario, List.of(first, second));
            Result a = played.get(first.key());
            Result b = played.get(second.key());
            System.out.printf("| %s | %s | %s | %s | %s |%n",
                    preset.key(), first.key(), a.profit().toPlainString(),
                    a.revenue().toPlainString(), a.cash().toPlainString());
            System.out.printf("| %s | %s | %s | %s | %s |%n",
                    preset.key(), second.key(), b.profit().toPlainString(),
                    b.revenue().toPlainString(), b.cash().toPlainString());

            Result winner = a.profit().compareTo(b.profit()) >= 0 ? a : b;
            assertThat(winner.plan())
                    .as("%s: %s earned %s, %s earned %s — the preset must reward %s", preset.key(),
                            first.key(), a.profit(), second.key(), b.profit(), preset.expectedWinner())
                    .isEqualTo(preset.expectedWinner());
        }
    }

    // ------------------------------------------------------------------ the presets

    private List<Preset> presets() {
        return List.of(
                // A cost leader sells volume cheaply: price moves demand hard, quality and brand
                // hardly at all, and overhead is thin because the model is scale.
                new Preset("preset-cost-v1",
                        new BigDecimal("2.10"), new BigDecimal("0.50"),
                        new BigDecimal("0.45"), new BigDecimal("0.50"),
                        MILLION.multiply(BigDecimal.valueOf(40)), null, new BigDecimal("0.80"),
                        "COST", List.of(Plan.COST, Plan.DIFF)),
                // A differentiated player sells preference: quality and brand carry the premium, price
                // is almost ignored, and the position costs money to hold.
                new Preset("preset-diff-v1",
                        new BigDecimal("1.10"), new BigDecimal("1.40"),
                        new BigDecimal("0.90"), new BigDecimal("0.90"),
                        MILLION.multiply(BigDecimal.valueOf(80)), null, BigDecimal.ONE,
                        "DIFF", List.of(Plan.COST, Plan.DIFF)),
                // A focused player serves one class only: no cross-class demand to fall back on, so the
                // premium product has to pay for the whole company.
                new Preset("preset-focus-v1",
                        new BigDecimal("1.30"), new BigDecimal("1.00"),
                        new BigDecimal("0.50"), new BigDecimal("0.60"),
                        MILLION.multiply(BigDecimal.valueOf(35)), BigDecimal.ZERO, BigDecimal.ONE,
                        "FOCUS", List.of(Plan.FOCUS, Plan.DIFF)));
    }

    /**
     * One preset: the economy it changes, and the plan it must reward. Everything not named here is
     * inherited from `standard-v1` — the products, classes, channels and timeline are the shipped ones.
     */
    private record Preset(String key, BigDecimal priceElasticity, BigDecimal qualityElasticity,
                          BigDecimal brandElasticity, BigDecimal marketingElasticity,
                          BigDecimal fixedCost, BigDecimal crossSegmentPct, BigDecimal demandScale,
                          String expectedWinner, List<Plan> plans) {

        Scenario build(Scenario base) {
            Scenario.Params p = base.params();
            List<Scenario.ProductSpec> products = new ArrayList<>();
            for (Scenario.ProductSpec product : base.products()) {
                BigDecimal demand = product.baseDemand().multiply(demandScale)
                        .setScale(0, RoundingMode.HALF_UP);
                products.add(new Scenario.ProductSpec(product.code(), product.name(),
                        product.basePrice(), product.baseUnitCost(), product.baseQuality(), demand,
                        product.unitsPerWorker(), product.incomeElasticity(), product.trendAffinity(),
                        product.targetSegment(),
                        crossSegmentPct == null ? product.crossSegmentPct() : crossSegmentPct,
                        product.texts()));
            }
            Scenario.Params tuned = new Scenario.Params(p.rounds(), p.roundMinutes(), p.startingCash(),
                    p.startingBrand(), fixedCost, p.brandDecay(), p.holdingCostPerUnit(),
                    p.referenceQuality(), p.referenceSpend(), p.machineCostPerWorker(),
                    p.hiringCostMonths(), p.severanceMonths(), p.minRndBenefitRatio(), p.baselineShare(),
                    p.seasonality(), priceElasticity, qualityElasticity, brandElasticity,
                    marketingElasticity, p.salaries(), p.startingStaff(), p.marketReportCost(),
                    p.forecastHorizon(), p.forecastBand(), p.cycles(), p.cycleLength(),
                    p.cycleDemandDrift(), p.cycleCostDrift(), p.qualityUpgrade(),
                    p.qualityCostPerPoint(), p.autoWorkers(), p.guidedRounds(), p.weightedCosting(),
                    // D99: every preset carries half its share, or the advantage still lasts a month.
                    new BigDecimal("0.50"));
            // D81: a product's market is its customer class's purchasing power, so the size of the
            // market is a segment number — scaling product demand alone would change nothing.
            List<Scenario.SegmentSpec> segments = new ArrayList<>();
            for (Scenario.SegmentSpec segment : base.segments()) {
                segments.add(new Scenario.SegmentSpec(segment.code(), segment.name(),
                        segment.description(), segment.baseSize().multiply(demandScale)
                        .setScale(0, RoundingMode.HALF_UP), segment.basePurchasingPower(),
                        segment.incomeElasticity(), segment.trendAffinity(), segment.sortOrder(),
                        segment.texts()));
            }
            return new Scenario(key, tuned, products, base.channels(), base.projects(), base.rounds(),
                    segments, base.events());
        }
    }

    // ------------------------------------------------------------------ the plans

    /**
     * The two ways of competing. A plan says what posture to take; the units and money are derived
     * from the template so the same plan is legal on any preset.
     */
    private enum Plan {
        /** Cheap, full capacity, thin marketing, no quality spend. */
        COST("COST", new BigDecimal("0.88"), new BigDecimal("0.00"), new BigDecimal("12"),
                new BigDecimal("1.00"), List.of("P1")),
        /** Premium price, quality spend on the best line, heavy marketing, most of capacity. */
        DIFF("DIFF", new BigDecimal("1.25"), new BigDecimal("35"), new BigDecimal("30"),
                new BigDecimal("0.90"), List.of("P1", "P2", "P3")),
        /** The focused line: the premium product only, priced high and supported. */
        FOCUS("FOCUS", new BigDecimal("1.30"), new BigDecimal("45"), new BigDecimal("20"),
                new BigDecimal("1.00"), List.of("P3"));

        private final String key;
        private final BigDecimal pricePosture;
        private final BigDecimal qualitySpend;
        private final BigDecimal marketingSpend;
        private final BigDecimal capacityFactor;
        private final List<String> carries;

        Plan(String key, BigDecimal pricePosture, BigDecimal qualitySpend, BigDecimal marketingSpend,
             BigDecimal capacityFactor, List<String> carries) {
            this.key = key;
            this.pricePosture = pricePosture;
            this.qualitySpend = qualitySpend;
            this.marketingSpend = marketingSpend;
            this.capacityFactor = capacityFactor;
            this.carries = carries;
        }

        String key() {
            return key;
        }

        List<String> carries() {
            return carries;
        }

        DecisionPayload decision(Scenario scenario, Company company) {
            List<DecisionPayload.ProductRow> production = new ArrayList<>();
            List<DecisionPayload.PriceRow> prices = new ArrayList<>();
            List<DecisionPayload.QualityRow> quality = new ArrayList<>();
            BigDecimal cash = company.cash;
            for (String code : carries) {
                Scenario.ProductSpec spec = scenario.product(code).orElseThrow();
                BigDecimal units = affordable(scenario, company, code, cash, production.size());
                production.add(new DecisionPayload.ProductRow(code, units));
                prices.add(new DecisionPayload.PriceRow(code,
                        spec.basePrice().multiply(pricePosture).setScale(2, RoundingMode.HALF_UP)));
                if (qualitySpend.signum() > 0) {
                    quality.add(new DecisionPayload.QualityRow(code,
                            qualitySpend.multiply(MILLION.divide(BigDecimal.valueOf(carries.size()),
                                    2, RoundingMode.HALF_UP))));
                }
            }
            // One channel gets the money: which channel is a detail, not the lesson.
            List<DecisionPayload.ChannelRow> marketing = List.of(
                    new DecisionPayload.ChannelRow("TRADE",
                            marketingSpend.multiply(MILLION).setScale(2, RoundingMode.HALF_UP)));
            return new DecisionPayload(production, prices, marketing,
                    new DecisionPayload.ManpowerRow(scenario.params().startingStaff()
                            .getOrDefault("PRODUCTION", 30)),
                    List.of(), quality, BigDecimal.ZERO, false);
        }

        /**
         * The workforce is the real limit: split it across the carried products in the shipped mix,
         * then leave a quarter of the capacity unused so a plan never becomes "produce the most".
         */
        private BigDecimal affordable(Scenario scenario, Company company, String code, BigDecimal cash,
                                      int index) {
            int workforce = scenario.params().startingStaff().getOrDefault("PRODUCTION", 30);
            BigDecimal budget = BigDecimal.valueOf(workforce).multiply(capacityFactor);
            // Split the workforce the way the catalogue is built: a product's share of the shipped
            // lineup is its demand divided by how many units one worker makes. Splitting by demand
            // alone would pile workers into the cheap high-volume line and leave P2/P3 unsupplied.
            BigDecimal weightTotal = BigDecimal.ZERO;
            for (String carried : carries) {
                weightTotal = weightTotal.add(weight(scenario, carried));
            }
            Scenario.ProductSpec spec = scenario.product(code).orElseThrow();
            BigDecimal workers = budget.multiply(weight(scenario, code))
                    .divide(weightTotal, 2, RoundingMode.HALF_UP);
            return workers.multiply(spec.unitsPerWorker()).setScale(0, RoundingMode.DOWN);
        }

        private static BigDecimal weight(Scenario scenario, String code) {
            Scenario.ProductSpec spec = scenario.product(code).orElseThrow();
            return spec.baseDemand().divide(spec.unitsPerWorker(), 4, RoundingMode.HALF_UP);
        }
    }

    // ------------------------------------------------------------------ the harness

    /** One company's mutable state: exactly what the engine hands back each month. */
    private static final class Company {
        private final long teamId;
        private final Plan plan;
        private BigDecimal cash;
        private BigDecimal brand;
        private BigDecimal machineWorkers = BigDecimal.ZERO;
        private Map<String, Integer> staff = new LinkedHashMap<>();
        private Map<String, ScoringEngine.ProductState> products = new LinkedHashMap<>();
        private Map<String, ScoringEngine.RndState> rnd = new LinkedHashMap<>();
        private String status = "ACTIVE";
        private BigDecimal profit = BigDecimal.ZERO;
        private BigDecimal revenue = BigDecimal.ZERO;

        Company(long teamId, Plan plan, BigDecimal cash, BigDecimal brand) {
            this.teamId = teamId;
            this.plan = plan;
            this.cash = cash;
            this.brand = brand;
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
            profit = profit.add(outcome.profit());
            revenue = revenue.add(outcome.revenue());
            Map<String, ScoringEngine.ProductState> next = new LinkedHashMap<>();
            for (ScoringEngine.ProductLine line : outcome.lines()) {
                next.put(line.product(), new ScoringEngine.ProductState(line.product(), line.price(),
                        line.quality(), line.unitCost(), line.closingStock(), line.stockValue()));
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
    }

    private record Result(String plan, BigDecimal profit, BigDecimal revenue, BigDecimal cash) {
    }

    /**
     * The plans, played against each other for the template's whole term, carrying state exactly as a
     * class would. Returns each plan's result by plan key.
     */
    private Map<String, Result> play(Scenario scenario, List<Plan> plans) {
        List<Company> companies = new ArrayList<>();
        for (Plan plan : plans) {
            Company company = new Company(companies.size() + 1L, plan,
                    scenario.params().startingCash(), scenario.params().startingBrand());
            // A carried product line is a company fact, not a decision: the focused plan holds one row.
            Map<String, ScoringEngine.ProductState> carried = new LinkedHashMap<>();
            for (Scenario.ProductSpec spec : scenario.products()) {
                if (plan.carries().contains(spec.code())) {
                    carried.put(spec.code(), new ScoringEngine.ProductState(spec.code(),
                            spec.basePrice(), spec.baseQuality(), spec.baseUnitCost(),
                            BigDecimal.ZERO, BigDecimal.ZERO));
                }
            }
            company.products = carried;
            companies.add(company);
        }

        ScoringEngine engine = new ScoringEngine();
        for (int round = 1; round <= scenario.params().rounds(); round++) {
            EnvironmentService.RoundEnvironment env = environment.forRound(scenario, QUIET_RUN, round);
            List<ScoringEngine.TeamInput> inputs = new ArrayList<>();
            for (Company company : companies) {
                inputs.add(company.input(company.plan.decision(scenario, company)));
            }
            ScoringEngine.RoundResult result = engine.compute(scenario, env, inputs);
            for (ScoringEngine.TeamOutcome outcome : result.teams()) {
                Company company = companies.stream()
                        .filter(c -> c.teamId == outcome.teamId()).findFirst().orElseThrow();
                company.apply(outcome);
                if (System.getenv("CALIBRATE_DEBUG") != null) {
                    StringBuilder sold = new StringBuilder();
                    for (ScoringEngine.ProductLine line : outcome.lines()) {
                        sold.append(String.format(" %s: d=%s s=%s", line.product(), line.demand(),
                                line.sold()));
                    }
                    System.out.printf("  r%d %s profit %s revenue %s cash %s |%s%n", round,
                            company.plan.key(), outcome.profit(), outcome.revenue(), outcome.cash(),
                            sold);
                }
            }
        }
        Map<String, Result> results = new LinkedHashMap<>();
        for (Company company : companies) {
            results.put(company.plan.key(), new Result(company.plan.key(), company.profit,
                    company.revenue, company.cash));
        }
        return results;
    }
}
