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
import java.util.Optional;
import java.util.Set;

/**
 * The economy (ruling D18): a per-round timeline of indices, an event deck drawn once per run, and
 * the forecast a trainee can buy.
 *
 * <p>Demand does not move uniformly. Each product has an income elasticity, so the same downturn
 * barely touches the basic product and guts the premium one — that is the lesson, and it is one
 * number per product rather than per-scenario code.
 */
@Service
public class EnvironmentService {

    private static final BigDecimal ONE = BigDecimal.ONE;

    /**
     * The economy as the engine consumes it: market-wide indices plus one already-resolved demand
     * multiplier per product, so the engine does not need to know about elasticities or events.
     */
    public record RoundEnvironment(int round, BigDecimal demandIndex, BigDecimal costIndex,
                                   BigDecimal wageIndex, Map<String, BigDecimal> demandByProduct,
                                   List<String> activeEvents) {

        public static RoundEnvironment neutral(int round, Collection<String> products) {
            Map<String, BigDecimal> multipliers = new LinkedHashMap<>();
            for (String code : products) {
                multipliers.put(code, ONE);
            }
            return new RoundEnvironment(round, ONE, ONE, ONE, multipliers, List.of());
        }
    }

    private final JdbcTemplate jdbc;

    public EnvironmentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ the draw

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
        Scenario.RoundSpec base = roundSpec(scenario, round)
                .orElse(new Scenario.RoundSpec(round, ONE, ONE, ONE, "neutral"));

        BigDecimal demand = nz(base.demandIndex());
        BigDecimal cost = nz(base.costIndex());
        BigDecimal wage = nz(base.wageIndex());

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
            byProduct.put(product.code(), byProduct.get(product.code())
                    .multiply(BigDecimal.valueOf(elastic))
                    .setScale(6, RoundingMode.HALF_UP));
        }

        return new RoundEnvironment(round, demand.setScale(3, RoundingMode.HALF_UP),
                cost.setScale(3, RoundingMode.HALF_UP), wage.setScale(3, RoundingMode.HALF_UP),
                byProduct, List.copyOf(active));
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
            Scenario.RoundSpec base = roundSpec(scenario, target)
                    .orElse(new Scenario.RoundSpec(target, ONE, ONE, ONE, "neutral"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("round", target);
            row.put("demandIndex", noisy(nz(base.demandIndex()), runId, round, step, "demand", band));
            row.put("costIndex", noisy(nz(base.costIndex()), runId, round, step, "cost", band));
            row.put("wageIndex", noisy(nz(base.wageIndex()), runId, round, step, "wage", band));
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
        body.put("outlook", outlook(environment.demandIndex()));
        body.put("events", activeEventDetails(scenario, runId, round));
        return body;
    }

    // ------------------------------------------------------------------ internals

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

    private static Optional<Scenario.RoundSpec> roundSpec(Scenario scenario, int round) {
        return scenario.rounds().stream().filter(spec -> spec.round() == round).findFirst();
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
