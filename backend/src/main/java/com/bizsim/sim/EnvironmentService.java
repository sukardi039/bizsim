package com.bizsim.sim;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The economy (rulings D18, D19): a cycle template expanded into this run's rounds, an event deck
 * drawn once per run, a consumer-trend signal, and the forecast a trainee can buy.
 *
 * <p>Demand does not move uniformly. Each product has an income elasticity and a trend affinity, so
 * the same downturn and the same shift in consumer taste hit the basic and the premium line
 * differently — that is the lesson, and it is two numbers per product rather than per-scenario code.
 */
@Service
public class EnvironmentService {

    private static final BigDecimal ONE = BigDecimal.ONE;

    /**
     * The economy as the engine consumes it: market-wide indices plus one already-resolved demand
     * multiplier per product, so the engine needs to know nothing about elasticities, trends or
     * events.
     */
    public record RoundEnvironment(int round, BigDecimal demandIndex, BigDecimal costIndex,
                                   BigDecimal wageIndex, BigDecimal trendStrength, String trendLabel,
                                   Map<String, BigDecimal> demandByProduct,
                                   List<String> activeEvents) {

        public static RoundEnvironment neutral(int round, Collection<String> products) {
            Map<String, BigDecimal> multipliers = new LinkedHashMap<>();
            for (String code : products) {
                multipliers.put(code, ONE);
            }
            return new RoundEnvironment(round, ONE, ONE, ONE, BigDecimal.ZERO, null,
                    multipliers, List.of());
        }
    }

    private final JdbcTemplate jdbc;

    public EnvironmentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ setup

    /**
     * Expands the scenario's cycle template into this run's rounds and freezes the result, so the
     * session can cover two cycles or five, and every run keeps its own copy of the economy.
     */
    public void generateTimeline(Scenario scenario, long runId, int rounds) {
        int length = Math.max(1, scenario.params().cycleLength());
        if (scenario.rounds().isEmpty()) {
            return;
        }
        BigDecimal demandDrift = ONE.add(nz(scenario.params().cycleDemandDrift()));
        BigDecimal costDrift = ONE.add(nz(scenario.params().cycleCostDrift()));

        for (int round = 1; round <= rounds; round++) {
            int position = Math.floorMod(round - 1, length) + 1;
            int cycle = (round - 1) / length;
            Scenario.RoundSpec template = templateAt(scenario, position).orElse(null);
            if (template == null) {
                continue;
            }
            BigDecimal demandFactor = demandDrift.pow(cycle, java.math.MathContext.DECIMAL64);
            BigDecimal costFactor = costDrift.pow(cycle, java.math.MathContext.DECIMAL64);

            jdbc.update("""
                    insert into run_round_economy (run_id, round_number, demand_index, cost_index,
                                                   wage_index, trend_strength, trend_label)
                    values (?, ?, ?, ?, ?, ?, ?)
                    on conflict (run_id, round_number) do nothing
                    """, runId, round,
                    nz(template.demandIndex()).multiply(demandFactor).setScale(3, RoundingMode.HALF_UP),
                    nz(template.costIndex()).multiply(costFactor).setScale(3, RoundingMode.HALF_UP),
                    nz(template.wageIndex()).multiply(costFactor).setScale(3, RoundingMode.HALF_UP),
                    nz(template.trendStrength()).setScale(2, RoundingMode.HALF_UP),
                    template.trendLabel());
        }
    }

    /**
     * Decides which deck entries fire in this run, and when. Called once, when the run is created, so
     * the schedule is frozen: the facilitator can look it up in the debrief and nobody can re-roll it
     * by restarting the app.
     */
    public List<String> scheduleEvents(Scenario scenario, long runId) {
        List<String> scheduled = new ArrayList<>();
        for (Scenario.EventSpec event : scenario.events()) {
            if (Seed.uniform(runId + ":env:" + event.code()).compareTo(event.probability()) >= 0) {
                continue;
            }
            int span = Math.max(0, event.latestRound() - event.earliestRound());
            int offset = (int) Math.floor(Seed.uniform(runId + ":env-at:" + event.code()).doubleValue()
                    * (span + 1));
            int start = Math.min(event.earliestRound() + offset, event.latestRound());
            jdbc.update("""
                    insert into run_environment_event (run_id, event_code, starts_round, duration_months)
                    values (?, ?, ?, ?)
                    on conflict (run_id, event_code) do nothing
                    """, runId, event.code(), start, event.durationMonths());
            scheduled.add(event.code());
        }
        return scheduled;
    }

    // ------------------------------------------------------------------ the round

    public RoundEnvironment forRound(Scenario scenario, long runId, int round) {
        Scenario.RoundSpec base = runRound(runId, round)
                .or(() -> templateAt(scenario, Math.max(1, round)))
                .orElse(new Scenario.RoundSpec(round, ONE, ONE, ONE, BigDecimal.ZERO, "Stable",
                        "neutral"));

        BigDecimal demand = nz(base.demandIndex());
        BigDecimal cost = nz(base.costIndex());
        BigDecimal wage = nz(base.wageIndex());
        BigDecimal trend = nz(base.trendStrength());

        Map<String, BigDecimal> byProduct = new LinkedHashMap<>();
        for (Scenario.ProductSpec product : scenario.products()) {
            byProduct.put(product.code(), ONE);
        }

        List<String> active = new ArrayList<>();
        for (Scenario.EventSpec event : activeEvents(scenario, runId, round)) {
            active.add(event.code());
            cost = cost.multiply(nz(event.costMultiplier()));
            wage = wage.multiply(nz(event.wageMultiplier()));
            if (event.targetProduct() == null || event.targetProduct().isBlank()) {
                demand = demand.multiply(nz(event.demandMultiplier()));
            } else if (byProduct.containsKey(event.targetProduct())) {
                byProduct.put(event.targetProduct(), byProduct.get(event.targetProduct())
                        .multiply(nz(event.demandMultiplier())));
            }
        }

        for (Scenario.ProductSpec product : scenario.products()) {
            double elastic = Math.pow(demand.doubleValue(), nz(product.incomeElasticity()).doubleValue());
            BigDecimal trendFactor = ONE.add(trend.multiply(nz(product.trendAffinity())));
            byProduct.put(product.code(), byProduct.get(product.code())
                    .multiply(BigDecimal.valueOf(elastic))
                    .multiply(trendFactor)
                    .setScale(6, RoundingMode.HALF_UP));
        }

        return new RoundEnvironment(round, demand.setScale(3, RoundingMode.HALF_UP),
                cost.setScale(3, RoundingMode.HALF_UP), wage.setScale(3, RoundingMode.HALF_UP),
                trend.setScale(2, RoundingMode.HALF_UP), base.trendLabel(), byProduct,
                List.copyOf(active));
    }

    /** Descriptions of the events that have already started — public knowledge by then. */
    public List<Map<String, Object>> activeEventDetails(Scenario scenario, long runId, int round) {
        List<Map<String, Object>> details = new ArrayList<>();
        for (Scenario.EventSpec event : activeEvents(scenario, runId, round)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", event.code());
            row.put("name", event.name());
            row.put("description", event.description());
            row.put("startedRound", startOf(runId, event.code()));
            details.add(row);
        }
        return details;
    }

    // ------------------------------------------------------------------ the forecast

    /**
     * The paid market review. Built from the published timeline only — it never reads the event deck
     * or the run's draw, so paying for the report cannot reveal a hidden event, only the ordinary
     * economics with a band around it.
     */
    public List<Map<String, Object>> forecast(Scenario scenario, long runId, int round) {
        int horizon = Math.max(1, scenario.params().forecastHorizon());
        BigDecimal band = nz(scenario.params().forecastBand());
        List<Map<String, Object>> rows = new ArrayList<>();

        for (int step = 1; step <= horizon; step++) {
            int target = round + step;
            Scenario.RoundSpec base = runRound(runId, target)
                    .or(() -> templateAt(scenario, Math.max(1, target)))
                    .orElse(new Scenario.RoundSpec(target, ONE, ONE, ONE, BigDecimal.ZERO,
                            "Stable", "neutral"));

            BigDecimal demand = noisy(nz(base.demandIndex()), runId, round, step, "demand", band);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("round", target);
            row.put("demandIndex", demand);
            row.put("costIndex", noisy(nz(base.costIndex()), runId, round, step, "cost", band));
            row.put("wageIndex", noisy(nz(base.wageIndex()), runId, round, step, "wage", band));
            row.put("trendStrength", noisy(nz(base.trendStrength()), runId, round, step, "trend",
                    band).setScale(2, RoundingMode.HALF_UP));
            row.put("trendLabel", base.trendLabel());
            row.put("band", band);
            rows.add(row);
        }
        for (Map<String, Object> row : rows) {
            row.put("outlook", outlook((BigDecimal) row.get("demandIndex")));
        }
        return rows;
    }

    public Map<String, Object> conditions(Scenario scenario, long runId, int round) {
        RoundEnvironment environment = forRound(scenario, runId, round);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("round", round);
        body.put("demandIndex", environment.demandIndex());
        body.put("costIndex", environment.costIndex());
        body.put("wageIndex", environment.wageIndex());
        body.put("trendStrength", environment.trendStrength());
        body.put("trendLabel", environment.trendLabel());
        body.put("outlook", outlook(environment.demandIndex()));
        body.put("events", activeEventDetails(scenario, runId, round));
        return body;
    }

    // ------------------------------------------------------------------ internals

    private Optional<Scenario.RoundSpec> runRound(long runId, int round) {
        return jdbc.query("""
                select demand_index, cost_index, wage_index, trend_strength, trend_label
                from run_round_economy where run_id = ? and round_number = ?
                """, (rs, i) -> new Scenario.RoundSpec(round, rs.getBigDecimal("demand_index"),
                rs.getBigDecimal("cost_index"), rs.getBigDecimal("wage_index"),
                rs.getBigDecimal("trend_strength"), rs.getString("trend_label"), null),
                runId, round).stream().findFirst();
    }

    private static Optional<Scenario.RoundSpec> templateAt(Scenario scenario, int position) {
        return scenario.rounds().stream().filter(spec -> spec.round() == position).findFirst();
    }

    private List<Scenario.EventSpec> activeEvents(Scenario scenario, long runId, int round) {
        Set<String> codes = new HashSet<>();
        jdbc.query("""
                select event_code from run_environment_event
                where run_id = ? and ? between starts_round and starts_round + duration_months - 1
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> codes.add(rs.getString(1)),
                runId, round);
        return scenario.events().stream().filter(event -> codes.contains(event.code())).toList();
    }

    private Integer startOf(long runId, String code) {
        return jdbc.query("select starts_round from run_environment_event where run_id = ? and event_code = ?",
                (rs, i) -> rs.getInt(1), runId, code).stream().findFirst().orElse(null);
    }

    private static BigDecimal noisy(BigDecimal value, long runId, int round, int step, String kind,
                                    BigDecimal band) {
        double draw = Seed.uniform(runId + ":forecast:" + round + ":" + step + ":" + kind).doubleValue();
        double factor = 1 + (draw - 0.5) * 2 * band.doubleValue();
        return value.multiply(BigDecimal.valueOf(factor)).setScale(3, RoundingMode.HALF_UP);
    }

    private static String outlook(BigDecimal demandIndex) {
        if (demandIndex.compareTo(new BigDecimal("1.020")) > 0) {
            return "EXPANDING";
        }
        if (demandIndex.compareTo(new BigDecimal("0.980")) < 0) {
            return "CONTRACTING";
        }
        return "STEADY";
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? ONE : value;
    }
}
