package com.bizsim.sim;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The deterministic scoring engine (decision 4). No randomness, no I/O, no clock: same inputs, same
 * numbers, which is what makes a debrief and a dispute resolvable.
 *
 * <p>The order below is the contract described in {@code docs/SIMULATION_MODEL.md} §4. Money is
 * {@link BigDecimal} at scale 2 (HALF_UP); attractiveness and share use {@code double} because they
 * are ratios, and only cash amounts are rounded.
 *
 * <p>Cash and profit differ on purpose: materials are paid on <em>produced</em> units, while COGS is
 * charged on <em>sold</em> units. Building stock therefore costs cash before it costs profit — the
 * working-capital lesson that v2 extends (D15).
 */
@Component
public class ScoringEngine {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MONEY_SCALE = 2;
    private static final int UNIT_SCALE = 2;

    /**
     * The pool table is sized for a full run (decision 5: 10 companies). A smaller cohort would
     * otherwise face a market five times its combined capacity, sell out every month and make
     * "market share" meaningless — so the market shrinks with the cohort, and the ratio of demand to
     * capacity stays the same from 2 players to 10.
     */
    private static final int REFERENCE_COMPANIES = 10;

    // ------------------------------------------------------------------ inputs

    /**
     * One product line as the company holds it. {@code stockValue} is what the units in stock cost
     * when they were paid for — the basis weighted-average costing charges against COGS (D79); a
     * template on REPLACEMENT costing never reads it.
     */
    public record ProductState(String code, BigDecimal price, BigDecimal quality,
                               BigDecimal unitCost, BigDecimal stock, BigDecimal stockValue) {
    }

    public record RndState(String project, BigDecimal invested, int monthsInvested, Integer startedRound,
                           String status, BigDecimal benefitFactor) {
    }

    public record TeamInput(long teamId, String status, BigDecimal cash, BigDecimal brand,
                            BigDecimal machineWorkers, Map<String, Integer> staff,
                            Map<String, ProductState> products, Map<String, RndState> rnd,
                            DecisionPayload decision) {
    }

    // ------------------------------------------------------------------ outputs

    public record ProductLine(String product, BigDecimal planned, BigDecimal produced,
                              BigDecimal openingStock, BigDecimal demand, BigDecimal sold,
                              BigDecimal closingStock, BigDecimal lost, BigDecimal price,
                              BigDecimal unitCost, BigDecimal quality, BigDecimal revenue,
                              BigDecimal cogs,
                              /** The cost per unit COGS was charged at: replacement or average (D79). */
                              BigDecimal averageCost,
                              /** What the closing stock cost, at the basis this round used. */
                              BigDecimal stockValue) {
    }

    public record RndUpdate(String project, BigDecimal invested, int monthsInvested, Integer startedRound,
                            String status, BigDecimal benefitFactor, Integer completedRound,
                            BigDecimal threshold) {
    }

    public record TeamOutcome(long teamId, String status, BigDecimal cash, BigDecimal brand,
                              BigDecimal machineWorkers, Map<String, Integer> staff,
                              Map<String, BigDecimal> stock, List<ProductLine> lines,
                              BigDecimal revenue, BigDecimal cogs, BigDecimal payroll, BigDecimal marketing,
                              BigDecimal rndSpend, BigDecimal fixedCost, BigDecimal holdingCost,
                              BigDecimal hiringCost, BigDecimal capacityCost, BigDecimal reportCost,
                              BigDecimal qualitySpend,
                              BigDecimal openingCash, BigDecimal profit, BigDecimal cashChange,
                              BigDecimal lostSales,
                              BigDecimal capacityUsed, BigDecimal capacityAvailable,
                              List<RndUpdate> rndUpdates,
                              Map<String, BigDecimal> stockValues, BigDecimal stockValueChange,
                              BigDecimal refund) {
    }

    public record Commitment(BigDecimal materials, BigDecimal payroll, BigDecimal hiring,
                             BigDecimal marketing, BigDecimal rnd, BigDecimal fixedCost,
                             BigDecimal holding, BigDecimal capacity, BigDecimal quality,
                             BigDecimal discretionary,
                             BigDecimal total, BigDecimal plannedWorkers, BigDecimal capacityUsed,
                             BigDecimal capacityAvailable) {
    }

    public record RoundResult(List<TeamOutcome> teams) {
    }

    private record Effects(Map<String, BigDecimal> costMult, Map<String, BigDecimal> upwMult,
                           Map<String, BigDecimal> qualityAdd, Map<String, BigDecimal> attractMult) {
    }

    private record Plan(Map<String, BigDecimal> planned, Map<String, BigDecimal> produced,
                        Map<String, BigDecimal> prices, Map<String, BigDecimal> effectiveCost,
                        Map<String, BigDecimal> effectiveQuality,
                        Map<String, BigDecimal> qualityBought,
                        Effects effects,
                        Map<String, BigDecimal> spendByChannel, Map<String, BigDecimal> investByProject,
                        Map<String, Integer> staff,
                        BigDecimal materials, BigDecimal payroll, BigDecimal hiring, BigDecimal marketing,
                        BigDecimal rndSpend, BigDecimal qualitySpend, BigDecimal holding,
                        BigDecimal capacityCost,
                        BigDecimal reportCost, BigDecimal machineAdded, BigDecimal plannedWorkers,
                        BigDecimal capacityUsed, BigDecimal capacityAvailable) {
    }

    // ------------------------------------------------------------------ entry points

    /** What the company would commit in cash if this decision were accepted, for submit-time checks. */
    public Commitment preview(Scenario scenario, EnvironmentService.RoundEnvironment environment,
                              TeamInput team) {
        Plan p = plan(scenario, environment, team);
        BigDecimal discretionary = p.materials().add(p.marketing()).add(p.rndSpend())
                .add(p.capacityCost()).add(p.hiring()).add(p.reportCost()).add(p.qualitySpend());
        BigDecimal total = discretionary.add(p.payroll()).add(p.holding())
                .add(scenario.params().fixedCostPerMonth());
        return new Commitment(p.materials(), p.payroll(), p.hiring(), p.marketing(), p.rndSpend(),
                scenario.params().fixedCostPerMonth(), p.holding(), p.capacityCost(), p.qualitySpend(),
                money(discretionary), money(total), units(p.plannedWorkers()), p.capacityUsed(),
                p.capacityAvailable());
    }

    public RoundResult compute(Scenario scenario, EnvironmentService.RoundEnvironment environment,
                               List<TeamInput> teams) {
        List<Plan> plans = new ArrayList<>();
        for (TeamInput team : teams) {
            plans.add(fitToCash(scenario, team, plan(scenario, environment, team)));
        }

        Map<Long, Map<String, Double>> demandByTeam =
                allocateDemand(scenario, environment, teams, plans);

        List<TeamOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < teams.size(); i++) {
            outcomes.add(settle(scenario, environment, teams.get(i), plans.get(i),
                    demandByTeam.get(teams.get(i).teamId())));
        }
        return new RoundResult(outcomes);
    }

    // ------------------------------------------------------------------ planning

    private Plan plan(Scenario scenario, EnvironmentService.RoundEnvironment environment, TeamInput team) {
        DecisionPayload d = team.decision() == null ? DecisionPayload.none() : team.decision();
        boolean insolvent = "INSOLVENT".equals(team.status());
        Effects effects = effects(scenario, team);

        Map<String, Integer> staff = new LinkedHashMap<>(team.staff());
        Integer productionTarget = d.productionTarget().orElse(null);
        if (!insolvent && productionTarget != null) {
            staff.put("PRODUCTION", Math.max(0, productionTarget));
        }

        BigDecimal workers = bd(staff.getOrDefault("PRODUCTION", 0)).add(nz(team.machineWorkers()));

        Map<String, BigDecimal> planned = new LinkedHashMap<>();
        Map<String, BigDecimal> produced = new LinkedHashMap<>();
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        Map<String, BigDecimal> effectiveCost = new LinkedHashMap<>();
        Map<String, BigDecimal> effectiveQuality = new LinkedHashMap<>();
        Map<String, BigDecimal> qualityBought = new LinkedHashMap<>();
        BigDecimal qualitySpend = ZERO;

        for (Scenario.ProductSpec spec : scenario.products()) {
            ProductState state = team.products().get(spec.code());
            BigDecimal stock = state == null ? ZERO : nz(state.stock());
            BigDecimal currentPrice = state == null ? spec.basePrice() : nz(state.price());
            BigDecimal currentQuality = state == null ? spec.baseQuality() : nz(state.quality());

            BigDecimal price = d.priceOf(spec.code());
            prices.put(spec.code(), price != null && price.signum() > 0 ? price : currentPrice);
            effectiveCost.put(spec.code(), money(nz(state == null ? spec.baseUnitCost() : state.unitCost())
                    .multiply(effects.costMult().getOrDefault(spec.code(), ONE))
                    .multiply(nz(environment.costIndex()))));

            // D15: quality bought this month is added on top of whatever R&D has delivered, and the
            // line stops at 100 -- money that cannot buy another point is simply not charged.
            BigDecimal quality = currentQuality
                    .add(effects.qualityAdd().getOrDefault(spec.code(), ZERO));
            BigDecimal bought = ZERO;
            BigDecimal rate = nz(scenario.params().qualityCostPerPoint());
            if (scenario.params().qualityUpgrade() && !insolvent && rate.signum() > 0) {
                BigDecimal wanted = nz(d.qualityInvest(spec.code()));
                if (wanted.signum() > 0) {
                    BigDecimal room = HUNDRED.subtract(quality).max(ZERO);
                    bought = min(div(wanted, rate), room).setScale(2, RoundingMode.HALF_UP);
                    qualitySpend = qualitySpend.add(bought.multiply(rate));
                }
            }
            qualityBought.put(spec.code(), bought);
            effectiveQuality.put(spec.code(), quality.add(bought));

            BigDecimal units = insolvent ? ZERO : nz(d.units(spec.code())).max(ZERO);
            planned.put(spec.code(), units);
            produced.put(spec.code(), units);
        }

        // Capacity is worker-equivalents: planned units / (units per worker x R&D uplift).
        BigDecimal required = ZERO;
        for (Scenario.ProductSpec spec : scenario.products()) {
            BigDecimal upw = spec.unitsPerWorker().multiply(effects.upwMult().getOrDefault(spec.code(), ONE));
            required = required.add(div(produced.get(spec.code()), upw));
        }
        BigDecimal capacityFactor = ONE;
        if (required.signum() > 0) {
            capacityFactor = workers.signum() <= 0 ? ZERO : min(ONE, div(workers, required));
        }
        if (capacityFactor.compareTo(ONE) < 0) {
            for (String code : produced.keySet()) {
                produced.put(code, units(produced.get(code).multiply(capacityFactor)));
            }
        }

        Map<String, BigDecimal> spendByChannel = new LinkedHashMap<>();
        BigDecimal marketing = ZERO;
        for (Scenario.ChannelSpec channel : scenario.channels()) {
            BigDecimal spend = insolvent ? ZERO : nz(d.spend(channel.code()));
            spendByChannel.put(channel.code(), spend);
            marketing = marketing.add(spend);
        }

        Map<String, BigDecimal> investByProject = new LinkedHashMap<>();
        BigDecimal rndSpend = ZERO;
        for (Scenario.ProjectSpec project : scenario.projects()) {
            BigDecimal invest = insolvent ? ZERO : nz(d.invest(project.code()));
            investByProject.put(project.code(), invest);
            rndSpend = rndSpend.add(invest);
        }
        BigDecimal capacityCost = insolvent ? ZERO : nz(d.capacityPurchaseOrZero());

        BigDecimal materials = ZERO;
        for (Scenario.ProductSpec spec : scenario.products()) {
            materials = materials.add(produced.get(spec.code()).multiply(effectiveCost.get(spec.code())));
        }
        materials = money(materials);

        BigDecimal payroll = ZERO;
        BigDecimal hiring = ZERO;
        BigDecimal wage = nz(environment.wageIndex());
        for (Map.Entry<String, Integer> entry : staff.entrySet()) {
            BigDecimal salary = scenario.params().salaries().getOrDefault(entry.getKey(), ZERO)
                    .multiply(wage);
            payroll = payroll.add(salary.multiply(bd(entry.getValue())));
            int previous = team.staff().getOrDefault(entry.getKey(), 0);
            int delta = entry.getValue() - previous;
            if (delta > 0) {
                hiring = hiring.add(salary.multiply(bd(delta))
                        .multiply(nz(scenario.params().hiringCostMonths())));
            } else if (delta < 0) {
                hiring = hiring.add(salary.multiply(bd(-delta))
                        .multiply(nz(scenario.params().severanceMonths())));
            }
        }

        BigDecimal holding = ZERO;
        for (Scenario.ProductSpec spec : scenario.products()) {
            ProductState state = team.products().get(spec.code());
            if (state != null) {
                holding = holding.add(nz(state.stock())
                        .multiply(nz(scenario.params().holdingCostPerUnit())));
            }
        }

        BigDecimal capacityUsed = ZERO;
        for (Scenario.ProductSpec spec : scenario.products()) {
            BigDecimal upw = spec.unitsPerWorker().multiply(effects.upwMult().getOrDefault(spec.code(), ONE));
            capacityUsed = capacityUsed.add(div(produced.get(spec.code()), upw));
        }

        BigDecimal reportCost = Boolean.TRUE.equals(d.marketReport())
                ? nz(scenario.params().marketReportCost()) : ZERO;

        return new Plan(planned, produced, prices, effectiveCost, effectiveQuality, qualityBought,
                effects,
                spendByChannel, investByProject, staff, materials, money(payroll), money(hiring),
                money(marketing), money(rndSpend), money(qualitySpend), money(holding),
                money(capacityCost),
                money(reportCost),
                div(capacityCost, nz(scenario.params().machineCostPerWorker())),
                units(required), units(capacityUsed), workers);
    }

    /**
     * The bank refuses the purchase order, not the payroll: if materials do not fit in cash after the
     * other commitments, production is scaled down to what can be paid for. Unavoidable costs are
     * never cut — they are what push a company into insolvency (D11).
     */
    private Plan fitToCash(Scenario scenario, TeamInput team, Plan p) {
        BigDecimal fixed = nz(scenario.params().fixedCostPerMonth());
        BigDecimal other = p.payroll().add(p.hiring()).add(p.marketing()).add(p.rndSpend())
                .add(p.qualitySpend()).add(p.holding()).add(fixed).add(p.capacityCost())
                .add(p.reportCost());
        BigDecimal available = nz(team.cash()).subtract(other).max(ZERO);
        if (p.materials().compareTo(available) <= 0) {
            return p;
        }
        BigDecimal factor = p.materials().signum() == 0 ? ZERO : div(available, p.materials());
        Map<String, BigDecimal> produced = new LinkedHashMap<>();
        for (String code : p.produced().keySet()) {
            produced.put(code, units(p.produced().get(code).multiply(factor)));
        }
        BigDecimal materials = ZERO;
        BigDecimal capacityUsed = ZERO;
        for (Scenario.ProductSpec spec : scenario.products()) {
            materials = materials.add(produced.get(spec.code()).multiply(p.effectiveCost().get(spec.code())));
            BigDecimal upw = spec.unitsPerWorker()
                    .multiply(p.effects().upwMult().getOrDefault(spec.code(), ONE));
            capacityUsed = capacityUsed.add(div(produced.get(spec.code()), upw));
        }
        return new Plan(p.planned(), produced, p.prices(), p.effectiveCost(), p.effectiveQuality(),
                p.qualityBought(), p.effects(), p.spendByChannel(), p.investByProject(), p.staff(),
                money(materials),
                p.payroll(), p.hiring(), p.marketing(), p.rndSpend(), p.qualitySpend(), p.holding(),
                p.capacityCost(),
                p.reportCost(), p.machineAdded(), p.plannedWorkers(), units(capacityUsed),
                p.capacityAvailable());
    }

    // ------------------------------------------------------------------ market

    private Map<Long, Map<String, Double>> allocateDemand(Scenario scenario,
                                                          EnvironmentService.RoundEnvironment environment,
                                                          List<TeamInput> teams, List<Plan> plans) {
        Map<Long, Map<String, Double>> result = new LinkedHashMap<>();
        for (TeamInput team : teams) {
            result.put(team.teamId(), new LinkedHashMap<>());
        }
        double baselineShare = scenario.params().baselineShare().doubleValue();

        for (Scenario.ProductSpec spec : scenario.products()) {
            double[] attractiveness = new double[teams.size()];
            double sum = 0;
            for (int i = 0; i < teams.size(); i++) {
                attractiveness[i] = attractiveness(scenario, spec.code(), teams.get(i), plans.get(i));
                sum += attractiveness[i];
            }
            // D34: the market is a whole number of pieces and it is divided in whole pieces. A share
            // of a pool used to be rounded to two decimals, which left companies holding 0.94 of a
            // product while another product stocked out -- numbers nobody can act on.
            BigDecimal pool = wholePool(scenario, spec, environment, teams.size());
            int n = teams.size();
            // The cohort serves (1 - baselineShare) of the pool; the rest is the market this class
            // never sees. The allocation has to add up to exactly that target in whole pieces.
            BigDecimal target = pool.multiply(ONE.subtract(bd(baselineShare)))
                    .setScale(0, RoundingMode.HALF_UP);
            if (sum <= 0 || n == 0 || target.signum() <= 0) {
                for (TeamInput team : teams) {
                    result.get(team.teamId()).put(spec.code(), 0d);
                }
                continue;
            }
            BigDecimal[] raw = new BigDecimal[n];
            BigDecimal[] pieces = new BigDecimal[n];
            BigDecimal assigned = ZERO;
            for (int i = 0; i < n; i++) {
                double share = attractiveness[i] / sum;
                raw[i] = target.multiply(bd(share));
                pieces[i] = raw[i].setScale(0, RoundingMode.DOWN);
                assigned = assigned.add(pieces[i]);
            }
            // Largest remainder: the units the pool still has go to the companies whose fractional
            // part was cut most, one each, ties by team order. The piece counts then add up to the
            // market exactly instead of quietly losing or inventing units.
            int leftover = target.subtract(assigned).min(BigDecimal.valueOf(n)).intValue();
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                order.add(i);
            }
            order.sort((a, b) -> {
                int byRemainder = raw[b].subtract(pieces[b]).compareTo(raw[a].subtract(pieces[a]));
                return byRemainder != 0 ? byRemainder : Integer.compare(a, b);
            });
            for (int k = 0; k < leftover; k++) {
                pieces[order.get(k)] = pieces[order.get(k)].add(ONE);
            }
            for (int i = 0; i < n; i++) {
                result.get(teams.get(i).teamId()).put(spec.code(), pieces[i].doubleValue());
            }
        }
        return result;
    }

    /** Total units demanded across every product in a round, for a cohort of this size. */
    public BigDecimal marketPool(Scenario scenario, EnvironmentService.RoundEnvironment environment,
                                 int companies) {
        BigDecimal pool = ZERO;
        for (Scenario.ProductSpec spec : scenario.products()) {
            pool = pool.add(wholePool(scenario, spec, environment, companies));
        }
        return pool;
    }

    /**
     * The market for one product, in whole pieces: base demand scaled for the cohort, then for the
     * round's environment. Rounded once, here, so the allocation and the market-share denominator
     * agree on the same number (D34).
     *
     * <p>D81: when the product serves a customer class, the market is that class's purchasing power
     * — how many buyers there are times what each can afford — plus the stated percentage of the
     * other classes' purchasable demand. The class's economy, not a per-product index, decides.
     */
    private BigDecimal wholePool(Scenario scenario, Scenario.ProductSpec spec,
                                 EnvironmentService.RoundEnvironment environment, int companies) {
        Optional<Scenario.SegmentSpec> target = scenario.segmentOf(spec);
        if (target.isPresent()) {
            Scenario.SegmentSpec segment = target.get();
            BigDecimal own = units(nz(segment.baseSize())
                    .multiply(environment.segmentSize(segment.code()))
                    .multiply(environment.segmentPurchasingPower(segment.code())));
            BigDecimal others = ZERO;
            for (Scenario.SegmentSpec other : scenario.segments()) {
                if (other.code().equals(segment.code())) {
                    continue;
                }
                others = others.add(units(nz(other.baseSize())
                        .multiply(environment.segmentSize(other.code()))
                        .multiply(environment.segmentPurchasingPower(other.code()))));
            }
            BigDecimal uplift = nz(spec.crossSegmentPct()).multiply(others);
            BigDecimal scale = cohortScale(companies).multiply(scenario.seasonality(environment.round()));
            return own.add(uplift).multiply(scale).setScale(0, RoundingMode.HALF_UP);
        }
        return poolFor(scenario, spec, environment.round(), companies)
                .multiply(nz(environment.demandByProduct().getOrDefault(spec.code(), ONE)))
                .setScale(0, RoundingMode.HALF_UP);
    }

    private static BigDecimal cohortScale(int companies) {
        return BigDecimal.valueOf(Math.max(companies, 1))
                .divide(BigDecimal.valueOf(REFERENCE_COMPANIES), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal poolFor(Scenario scenario, Scenario.ProductSpec spec, int round, int companies) {
        return units(nz(spec.baseDemand())
                .multiply(scenario.seasonality(round))
                .multiply(cohortScale(companies)));
    }

    private double attractiveness(Scenario scenario, String productCode, TeamInput team, Plan plan) {
        Scenario.Params p = scenario.params();
        BigDecimal price = plan.prices().get(productCode);
        BigDecimal quality = plan.effectiveQuality().get(productCode);
        BigDecimal brand = nz(team.brand()).max(ONE);
        double reach = reach(scenario, plan);

        double a = Math.pow(div(nz(referencePrice(scenario, productCode)), price).doubleValue(),
                p.priceElasticity().doubleValue());
        a *= Math.pow(div(quality.max(ZERO), nz(p.referenceQuality())).doubleValue(),
                p.qualityElasticity().doubleValue());
        a *= Math.pow(brand.divide(new BigDecimal("50"), 6, RoundingMode.HALF_UP).doubleValue(),
                p.brandElasticity().doubleValue());
        a *= Math.pow(Math.max(reach, 0.01), p.marketingElasticity().doubleValue());
        a *= plan.effects().attractMult().getOrDefault(productCode, ONE).doubleValue();
        return a;
    }

    private static BigDecimal referencePrice(Scenario scenario, String productCode) {
        return scenario.product(productCode).map(Scenario.ProductSpec::basePrice).orElse(ONE);
    }

    /** Marketing reach: weighted spend against a reference spend, square-rooted for diminishing returns. */
    private double reach(Scenario scenario, Plan plan) {
        BigDecimal weighted = ZERO;
        for (Scenario.ChannelSpec channel : scenario.channels()) {
            weighted = weighted.add(nz(plan.spendByChannel().get(channel.code()))
                    .multiply(nz(channel.reachWeight())));
        }
        BigDecimal reference = nz(scenario.params().referenceSpend());
        if (reference.signum() <= 0) {
            return 1;
        }
        return Math.sqrt(div(weighted, reference).doubleValue());
    }

    // ------------------------------------------------------------------ settlement

    private TeamOutcome settle(Scenario scenario, EnvironmentService.RoundEnvironment environment,
                               TeamInput team, Plan plan, Map<String, Double> demand) {
        Scenario.Params p = scenario.params();
        int round = environment.round();
        List<ProductLine> lines = new ArrayList<>();
        Map<String, BigDecimal> stock = new LinkedHashMap<>();

        BigDecimal revenue = ZERO;
        BigDecimal cogs = ZERO;
        BigDecimal lostSales = ZERO;
        BigDecimal capacityUsed = ZERO;
        Map<String, BigDecimal> stockValues = new LinkedHashMap<>();
        BigDecimal stockValueChange = ZERO;
        boolean weighted = scenario.params().weightedCosting();

        for (Scenario.ProductSpec spec : scenario.products()) {
            ProductState state = team.products().get(spec.code());
            BigDecimal opening = state == null ? ZERO : nz(state.stock());
            BigDecimal produced = plan.produced().get(spec.code());
            BigDecimal price = plan.prices().get(spec.code());
            BigDecimal unitCost = plan.effectiveCost().get(spec.code());

            // Demand arrives allocated in whole pieces (D34); no re-scaling here.
            BigDecimal demanded = bd(demand.getOrDefault(spec.code(), 0.0)).setScale(0, RoundingMode.HALF_UP);
            BigDecimal available = opening.add(produced);
            BigDecimal sold = min(demanded, available);
            BigDecimal lost = demanded.subtract(sold).max(ZERO);
            BigDecimal closing = available.subtract(sold).max(ZERO);

            BigDecimal lineRevenue = money(sold.multiply(price));

            // D79. Replacement costing charges this month's cost for goods paid for last month, so a
            // company that stocked up before a cost rise is expensed at a price it never paid — and
            // with stock at zero at both ends, cash and profit stop agreeing. Weighted average charges
            // what the units in stock actually cost; then cash movement = profit + stock change.
            BigDecimal openingValue = state == null ? ZERO : nz(state.stockValue());
            BigDecimal addedValue = money(produced.multiply(unitCost));
            BigDecimal availableValue = openingValue.add(addedValue);
            BigDecimal lineCogs;
            BigDecimal averageCost;
            BigDecimal closingValue;
            if (weighted) {
                averageCost = available.signum() > 0
                        ? div(availableValue, available) : unitCost;
                lineCogs = money(sold.multiply(averageCost));
                closingValue = availableValue.subtract(lineCogs).max(ZERO);
            } else {
                averageCost = unitCost;
                lineCogs = money(sold.multiply(unitCost));
                closingValue = money(closing.multiply(unitCost));
            }

            revenue = revenue.add(lineRevenue);
            cogs = cogs.add(lineCogs);
            lostSales = lostSales.add(lost);
            stock.put(spec.code(), closing);
            stockValues.put(spec.code(), closingValue);
            stockValueChange = stockValueChange.add(closingValue.subtract(openingValue));

            lines.add(new ProductLine(spec.code(), units(plan.planned().get(spec.code())), produced,
                    units(opening), demanded, sold, units(closing), units(lost), price, unitCost,
                    units(plan.effectiveQuality().get(spec.code())), lineRevenue, lineCogs,
                    averageCost.setScale(2, RoundingMode.HALF_UP), closingValue));
        }

        // R&D: fixed duration, evaluated at the deadline (D10). Advantages reach the market next month.
        List<RndUpdate> rndUpdates = new ArrayList<>();
        BigDecimal refund = ZERO;
        for (Scenario.ProjectSpec project : scenario.projects()) {
            RndState state = team.rnd().get(project.code());
            String status = state == null ? "ACTIVE" : state.status();
            BigDecimal invested = state == null ? ZERO : nz(state.invested());
            int months = state == null ? 0 : state.monthsInvested();
            Integer started = state == null ? null : state.startedRound();
            BigDecimal factor = state == null ? ZERO : nz(state.benefitFactor());
            Integer completedRound = null;
            BigDecimal roll = null;

            BigDecimal invest = "ACTIVE".equals(status)
                    ? nz(plan.investByProject().getOrDefault(project.code(), ZERO)) : ZERO;
            if ("ACTIVE".equals(status) && (invest.signum() > 0 || started != null)) {
                if (started == null) {
                    started = round;
                }
                months = round - started + 1;
                invested = invested.add(invest);
                if (months >= project.durationMonths()) {
                    completedRound = round;
                    BigDecimal ratio = div(invested, project.hiddenCost());
                    BigDecimal minimum = nz(p.minRndBenefitRatio());

                    if (ratio.compareTo(ONE) >= 0) {
                        status = "COMPLETED";
                        factor = ONE;
                    } else if (!project.partialBenefit() || ratio.compareTo(minimum) < 0) {
                        // D17: partial credit is a per-project switch. Off means 100% or nothing.
                        status = "FAILED";
                        factor = ZERO;
                    } else {
                        BigDecimal progress = min(ONE,
                                div(ratio.subtract(minimum), ONE.subtract(minimum)));
                        String curve = project.benefitCurve() == null ? "LINEAR" : project.benefitCurve();
                        if ("CHANCE".equals(curve)) {
                            // The dice is a hidden threshold, drawn once per team+round+project from a
                            // seeded digest. Investment is compared against it, so the same inputs
                            // always produce the same answer on replay (decision 4) — the trainee
                            // just cannot see the number in advance.
                            BigDecimal threshold = minimum.add(ONE.subtract(minimum)
                                    .multiply(roll(team.teamId(), round, project.code())))
                                    .setScale(4, RoundingMode.HALF_UP);
                            roll = threshold;
                            boolean success = ratio.compareTo(threshold) >= 0;
                            factor = success ? ONE : ZERO;
                            status = success ? "COMPLETED" : "FAILED";
                        } else {
                            factor = "POWER".equals(curve)
                                    ? power(progress, project.benefitExponent())
                                    : progress;
                            status = "COMPLETED";
                        }
                    }

                    if ("COMPLETED".equals(status)) {
                        BigDecimal over = invested.subtract(project.hiddenCost());
                        if (over.signum() > 0 && project.refundsOverfunding()) {
                            refund = refund.add(over);
                            invested = project.hiddenCost();
                        }
                    }
                }
            }
            rndUpdates.add(new RndUpdate(project.code(), money(invested), months, started, status,
                    factor.setScale(4, RoundingMode.HALF_UP), completedRound, roll));
        }

        BigDecimal fixed = nz(p.fixedCostPerMonth());
        BigDecimal profit = revenue.subtract(cogs).subtract(plan.payroll()).subtract(plan.marketing())
                .subtract(plan.rndSpend()).subtract(plan.qualitySpend()).subtract(fixed)
                .subtract(plan.holding()).subtract(plan.hiring()).subtract(plan.reportCost());
        BigDecimal cashAfter = nz(team.cash()).add(revenue).subtract(plan.materials())
                .subtract(plan.payroll()).subtract(plan.marketing()).subtract(plan.rndSpend())
                .subtract(plan.qualitySpend()).subtract(fixed).subtract(plan.holding())
                .subtract(plan.hiring())
                .subtract(plan.capacityCost()).subtract(plan.reportCost()).add(refund);
        BigDecimal cashChange = cashAfter.subtract(nz(team.cash()));

        BigDecimal brandAfter = brand(scenario, team.brand(), plan.spendByChannel());

        String status = cashAfter.signum() < 0 ? "INSOLVENT" : "ACTIVE";

        return new TeamOutcome(team.teamId(), status, money(cashAfter), brandAfter,
                money(nz(team.machineWorkers()).add(plan.machineAdded())), plan.staff(), stock, lines,
                money(revenue), money(cogs), plan.payroll(), plan.marketing(), plan.rndSpend(),
                money(fixed), plan.holding(), plan.hiring(), plan.capacityCost(), plan.reportCost(),
                plan.qualitySpend(),
                money(nz(team.cash())),
                money(profit), money(cashChange), units(lostSales), plan.capacityUsed(),
                plan.capacityAvailable(), rndUpdates,
                stockValues, money(stockValueChange), money(refund));
    }

    /** Brand is a stock, not a flow: this month's spend is felt next month, and it decays (D5/marketing). */
    private BigDecimal brand(Scenario scenario, BigDecimal openingBrand, Map<String, BigDecimal> spendByChannel) {
        BigDecimal decay = nz(scenario.params().brandDecay());
        BigDecimal value = nz(openingBrand).multiply(ONE.subtract(decay));
        for (Scenario.ChannelSpec channel : scenario.channels()) {
            BigDecimal spend = nz(spendByChannel.get(channel.code()));
            if (spend.signum() > 0) {
                double points = channel.brandAlpha().doubleValue()
                        * Math.sqrt(spend.doubleValue() / 1_000_000d);
                value = value.add(bd(points));
            }
        }
        return money(value.max(ZERO).min(HUNDRED));
    }

    // ------------------------------------------------------------------ R&D effects

    private Effects effects(Scenario scenario, TeamInput team) {
        Map<String, BigDecimal> costMult = new LinkedHashMap<>();
        Map<String, BigDecimal> upwMult = new LinkedHashMap<>();
        Map<String, BigDecimal> qualityAdd = new LinkedHashMap<>();
        Map<String, BigDecimal> attractMult = new LinkedHashMap<>();
        for (Scenario.ProductSpec spec : scenario.products()) {
            costMult.put(spec.code(), ONE);
            upwMult.put(spec.code(), ONE);
            qualityAdd.put(spec.code(), ZERO);
            attractMult.put(spec.code(), ONE);
        }

        for (Scenario.ProjectSpec project : scenario.projects()) {
            RndState state = team.rnd().get(project.code());
            if (state == null || !"COMPLETED".equals(state.status())) {
                continue;
            }
            BigDecimal factor = nz(state.benefitFactor());
            if (factor.signum() <= 0) {
                continue;
            }
            BigDecimal amount = nz(project.advantageValue()).multiply(factor);
            List<String> targets = project.processWide()
                    ? new ArrayList<>(costMult.keySet())
                    : List.of(project.targetProduct());
            for (String code : targets) {
                if (!costMult.containsKey(code)) {
                    continue;
                }
                switch (project.advantageType()) {
                    case "UNIT_COST_DOWN" ->
                            costMult.put(code, costMult.get(code).multiply(ONE.subtract(amount)));
                    case "CAPACITY_UP" ->
                            upwMult.put(code, upwMult.get(code).multiply(ONE.add(amount)));
                    case "QUALITY_UP" ->
                            qualityAdd.put(code, qualityAdd.get(code).add(amount));
                    case "ATTRACTIVENESS_UP" ->
                            attractMult.put(code, attractMult.get(code).multiply(ONE.add(amount)));
                    default -> throw new IllegalStateException("unknown advantage type "
                            + project.advantageType());
                }
            }
        }
        return new Effects(costMult, upwMult, qualityAdd, attractMult);
    }

    // ------------------------------------------------------------------ numeric helpers

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    private static BigDecimal bd(int v) {
        return BigDecimal.valueOf(v);
    }

    private static BigDecimal div(BigDecimal a, BigDecimal b) {
        return b == null || b.signum() == 0 ? ZERO : a.divide(b, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal power(BigDecimal base, BigDecimal exponent) {
        double value = Math.pow(base.doubleValue(), nz(exponent).doubleValue());
        return min(ONE, bd(value)).max(ZERO);
    }

    /**
     * A uniform value in [0, 1) derived from the team, round and project. Deterministic on purpose:
     * the same round recomputed (a void, a retry, a dispute) must produce the same outcome, which is
     * what keeps the CHANCE curve compatible with decision 4.
     */
    private static BigDecimal roll(long teamId, int round, String projectCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((teamId + ":" + round + ":" + projectCode)
                    .getBytes(StandardCharsets.UTF_8));
            long bits = 0;
            for (int i = 0; i < 8; i++) {
                bits = (bits << 8) | (bytes[i] & 0xffL);
            }
            double uniform = (double) ((bits & 0x7fffffffffffffffL) >>> 11) / (double) (1L << 53);
            return bd(uniform);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal units(BigDecimal v) {
        return v.setScale(UNIT_SCALE, RoundingMode.HALF_UP);
    }
}
