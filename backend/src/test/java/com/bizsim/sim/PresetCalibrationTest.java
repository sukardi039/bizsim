package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
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
 * D100: each shipped preset must hand the win to the strategy it is named for.
 *
 * <p>A preset is a template whose elasticities, fixed cost, market size and (for cost leadership) unit
 * cost are tuned so one way of competing pays and another does not. That claim is only worth anything
 * if it is measured on the shipped rows: this loads each preset from the database and plays two plain
 * plans against each other for six rounds, with no events drawn (a run id no test creates).
 *
 * <p>The plans differ only in price posture, quality spend, marketing and which products the company
 * carries — never in cleverness of policy — so what separates them is the preset's economy. A preset
 * whose named plan loses is a bug in its numbers, not a preset.
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

    /** The preset, the two plans that contest it, and the one that has to win. */
    private record Contest(String preset, List<Plan> plans, String expectedWinner) {
    }

    @Test
    void each_preset_hands_the_win_to_the_strategy_it_is_named_for() {
        List<Contest> contests = List.of(
                new Contest("preset-cost-v1", List.of(Plan.COST, Plan.DIFF), "COST"),
                new Contest("preset-diff-v1", List.of(Plan.COST, Plan.DIFF), "DIFF"),
                new Contest("preset-focus-v1", List.of(Plan.FOCUS, Plan.DIFF), "FOCUS"));

        System.out.printf("%n| preset | plan | cumulative profit | revenue | cash |%n");
        System.out.printf("|---|---|---|---|---|%n");
        for (Contest contest : contests) {
            Scenario scenario = scenarios.load(contest.preset());
            Map<String, Result> played = play(scenario, contest.plans());
            for (Plan plan : contest.plans()) {
                Result result = played.get(plan.key());
                System.out.printf("| %s | %s | %s | %s | %s |%n", contest.preset(), plan.key(),
                        result.profit().toPlainString(), result.revenue().toPlainString(),
                        result.cash().toPlainString());
            }
            Result first = played.get(contest.plans().get(0).key());
            Result second = played.get(contest.plans().get(1).key());
            Result winner = first.profit().compareTo(second.profit()) >= 0 ? first : second;
            assertThat(winner.plan())
                    .as("%s: %s earned %s, %s earned %s — the preset must reward %s",
                            contest.preset(), first.plan(), first.profit(), second.plan(),
                            second.profit(), contest.expectedWinner())
                    .isEqualTo(contest.expectedWinner());
        }
    }

    // ------------------------------------------------------------------ the plans

    /**
     * The two ways of competing. A plan says what posture to take; the units and money are derived
     * from the template so the same plan is legal on any preset.
     */
    private enum Plan {
        /** Cheap, the mass line only, full capacity, thin marketing, no quality spend. */
        COST("COST", new BigDecimal("0.92"), new BigDecimal("0.00"), new BigDecimal("12"),
                new BigDecimal("1.00"), List.of("P1")),
        /** Premium price, quality spend on every line, heavy marketing, most of capacity. */
        DIFF("DIFF", new BigDecimal("1.25"), new BigDecimal("35"), new BigDecimal("30"),
                new BigDecimal("0.90"), List.of("P1", "P2", "P3")),
        /** The focused line: the premium product only, priced high and supported. */
        FOCUS("FOCUS", new BigDecimal("1.40"), new BigDecimal("45"), new BigDecimal("20"),
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
            for (String code : carries) {
                Scenario.ProductSpec spec = scenario.product(code).orElseThrow();
                production.add(new DecisionPayload.ProductRow(code, unitsFor(scenario, company, code)));
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
         * What to build for one product: enough to serve last month's customers, never past capacity.
         * Building to capacity regardless charges materials for stock nobody buys, which punishes the
         * volume plan for a planning mistake rather than for its economics. Round 1 has no history,
         * so a plan opens at 60% of capacity.
         */
        private BigDecimal unitsFor(Scenario scenario, Company company, String code) {
            BigDecimal capacity = capacityFor(scenario, code);
            BigDecimal last = company.lastSold.getOrDefault(code, BigDecimal.ZERO);
            BigDecimal target = last.signum() == 0
                    ? capacity.multiply(new BigDecimal("0.60"))
                    : last.multiply(new BigDecimal("1.05"));
            return target.min(capacity).setScale(0, RoundingMode.DOWN);
        }

        /** The workforce split the way the catalogue is built: demand divided by units per worker. */
        private BigDecimal capacityFor(Scenario scenario, String code) {
            int workforce = scenario.params().startingStaff().getOrDefault("PRODUCTION", 30);
            BigDecimal budget = BigDecimal.valueOf(workforce).multiply(capacityFactor);
            BigDecimal weightTotal = BigDecimal.ZERO;
            for (String carried : carries) {
                weightTotal = weightTotal.add(weight(scenario, carried));
            }
            Scenario.ProductSpec spec = scenario.product(code).orElseThrow();
            return budget.multiply(weight(scenario, code))
                    .divide(weightTotal, 4, RoundingMode.HALF_UP)
                    .multiply(spec.unitsPerWorker());
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
        private Map<String, BigDecimal> lastSold = new LinkedHashMap<>();
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
            Map<String, BigDecimal> sold = new LinkedHashMap<>();
            for (ScoringEngine.ProductLine line : outcome.lines()) {
                next.put(line.product(), new ScoringEngine.ProductState(line.product(), line.price(),
                        line.quality(), line.unitCost(), line.closingStock(), line.stockValue()));
                sold.put(line.product(), line.sold());
            }
            products = next;
            lastSold = sold;
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
     * class would. Both compete in the same class month after month: a plan played alone takes the
     * whole served market whatever its price, so price posture would never be tested.
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
