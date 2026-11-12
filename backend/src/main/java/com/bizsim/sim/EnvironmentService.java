package com.bizsim.sim;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * The economy (rulings D18, D19, D81-D84): a cycle template expanded into this run's rounds, an
 * event deck drawn once per run, a consumer-trend signal, and the forecast a trainee can buy.
 *
 * <p>D81 changed what moves. Before, an event multiplied a product's demand. Now the market is made
 * of <strong>customer classes</strong>, and an event moves two numbers per class: how many buyers
 * there are ({@code size}) and what each of them can spend ({@code purchasingPower}). A product
 * inherits the weather of the class it serves, so "premium demand collapses in a recession" is not a
 * special case in code — it is the premium class's effect row.
 *
 * <p>The round's numbers are frozen when the run is created (the timeline and the draw). The draw
 * also freezes what each card does, so editing a template cannot reach back into a class that has
 * already played.
 */
@Service
public class EnvironmentService {

    private static final BigDecimal ONE = BigDecimal.ONE;

    /**
     * One class of customer as this round sees it: the size multiplier relative to its base (the
     * demand index, the trend and active events already applied) and its purchasing-power index.
     */
    public record SegmentState(String code, String name, BigDecimal sizeIndex,
                               BigDecimal purchasingPower) {
    }

    /**
     * The economy as the engine consumes it: market-wide indices, one already-resolved state per
     * customer class, and one demand multiplier per product for legacy templates that still size a
     * product directly.
     */
    public record RoundEnvironment(int round, BigDecimal demandIndex, BigDecimal costIndex,
                                   BigDecimal wageIndex, BigDecimal trendStrength, String trendLabel,
                                   Map<String, SegmentState> segments,
                                   Map<String, BigDecimal> demandByProduct,
                                   List<String> activeEvents) {

        public static RoundEnvironment neutral(int round, Collection<String> products) {
            Map<String, BigDecimal> multipliers = new LinkedHashMap<>();
            for (String code : products) {
                multipliers.put(code, ONE);
            }
            return new RoundEnvironment(round, ONE, ONE, ONE, BigDecimal.ZERO, null,
                    Map.of(), multipliers, List.of());
        }

        public BigDecimal segmentSize(String code) {
            SegmentState state = segments.get(code);
            return state == null ? ONE : state.sizeIndex();
        }

        public BigDecimal segmentPurchasingPower(String code) {
            SegmentState state = segments.get(code);
            return state == null ? ONE : state.purchasingPower();
        }
    }

    /** A card that is firing this round, with the numbers frozen at the draw. */
    private record Resolved(Scenario.EventSpec spec, int startsRound, BigDecimal demandMultiplier,
                            BigDecimal costMultiplier, BigDecimal wageMultiplier,
                            String targetProduct, List<Scenario.EventEffect> effects) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public EnvironmentService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
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
        String lang = runLang(runId);
        BigDecimal demandDrift = ONE.add(nz(scenario.params().cycleDemandDrift()));
        BigDecimal costDrift = ONE.add(nz(scenario.params().cycleCostDrift()));

        for (int round = 1; round <= rounds; round++) {
            int position = Math.floorMod(round - 1, length) + 1;
            int cycle = (round - 1) / length;
            Scenario.RoundSpec template = templateAt(scenario, lang, position).orElse(null);
            if (template == null) {
                continue;
            }
            BigDecimal demandFactor = demandDrift.pow(cycle, java.math.MathContext.DECIMAL64);
            BigDecimal costFactor = costDrift.pow(cycle, java.math.MathContext.DECIMAL64);

            jdbc.update("""
                    insert into run_round_economy (run_id, round_number, demand_index, cost_index,
                                                   wage_index, trend_strength, trend_label,
                                                   description)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (run_id, round_number) do nothing
                    """, runId, round,
                    nz(template.demandIndex()).multiply(demandFactor).setScale(3, RoundingMode.HALF_UP),
                    nz(template.costIndex()).multiply(costFactor).setScale(3, RoundingMode.HALF_UP),
                    nz(template.wageIndex()).multiply(costFactor).setScale(3, RoundingMode.HALF_UP),
                    nz(template.trendStrength()).setScale(2, RoundingMode.HALF_UP),
                    template.trendLabel(), template.description());
        }
    }

    /**
     * Decides which deck entries fire in this run, and when. Called once, when the run is created, so
     * the schedule is frozen: the facilitator can look it up in the debrief and nobody can re-roll it
     * by restarting the app. The card's numbers are frozen here too (D84) — a template edit after the
     * draw must not change a class that is already playing.
     *
     * <p>D86: only the run's own language deck is drawn, and the seed uses the <em>canonical</em> code
     * (the language suffix stripped), so the same run gets the same weather whichever language its
     * class reads.
     */
    public List<String> scheduleEvents(Scenario scenario, long runId) {
        List<String> scheduled = new ArrayList<>();
        for (Scenario.EventSpec event : scenario.eventsOf(runLang(runId))) {
            String seedCode = canonical(event.code());
            if (Seed.uniform(runId + ":env:" + seedCode).compareTo(event.probability()) >= 0) {
                continue;
            }
            int span = Math.max(0, event.latestRound() - event.earliestRound());
            int offset = (int) Math.floor(Seed.uniform(runId + ":env-at:" + seedCode).doubleValue()
                    * (span + 1));
            int start = Math.min(event.earliestRound() + offset, event.latestRound());
            jdbc.update("""
                    insert into run_environment_event (run_id, event_code, starts_round, duration_months,
                                                       demand_multiplier, cost_multiplier,
                                                       wage_multiplier, target_product, effects)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    on conflict (run_id, event_code) do nothing
                    """, runId, event.code(), start, event.durationMonths(),
                    nz(event.demandMultiplier()), nz(event.costMultiplier()),
                    nz(event.wageMultiplier()), event.targetProduct(), effectsJson(event.effects()));
            scheduled.add(event.code());
        }
        return scheduled;
    }

    /** `EV-INFLATION-ZH` and `EV-INFLATION` are the same card in two languages (D86). */
    static String canonical(String code) {
        return code.replaceFirst("-[A-Z]{2}$", "");
    }

    private String effectsJson(List<Scenario.EventEffect> effects) {
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Scenario.EventEffect effect : effects == null ? List.<Scenario.EventEffect>of() : effects) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("segmentCode", effect.segmentCode());
                row.put("sizeMultiplier", effect.sizeMultiplier());
                row.put("purchasingPowerMultiplier", effect.purchasingPowerMultiplier());
                rows.add(row);
            }
            return json.writeValueAsString(rows);
        } catch (Exception e) {
            throw new IllegalStateException("cannot freeze event effects", e);
        }
    }

    // ------------------------------------------------------------------ the round

    public RoundEnvironment forRound(Scenario scenario, long runId, int round) {
        String lang = runLang(runId);
        Scenario.RoundSpec base = runRound(runId, round)
                .or(() -> templateAt(scenario, lang, Math.max(1, round)))
                .orElse(new Scenario.RoundSpec(lang, round, ONE, ONE, ONE, BigDecimal.ZERO,
                        "Stable", "neutral", null));

        BigDecimal demand = nz(base.demandIndex());
        BigDecimal cost = nz(base.costIndex());
        BigDecimal wage = nz(base.wageIndex());
        BigDecimal trend = nz(base.trendStrength());

        // Sizes are absolute unit counts at reference conditions; purchasing power is an index.
        Map<String, BigDecimal> size = new LinkedHashMap<>();
        Map<String, BigDecimal> power = new LinkedHashMap<>();
        for (Scenario.SegmentSpec segment : scenario.segments()) {
            double elastic = Math.pow(demand.doubleValue(), nz(segment.incomeElasticity()).doubleValue());
            BigDecimal trendFactor = ONE.add(trend.multiply(nz(segment.trendAffinity())));
            size.put(segment.code(), nz(segment.baseSize())
                    .multiply(BigDecimal.valueOf(elastic)).multiply(trendFactor));
            power.put(segment.code(), nz(segment.basePurchasingPower()));
        }

        // Legacy product-level multipliers, kept for a template that sized demand per product.
        Map<String, BigDecimal> legacyDemand = new LinkedHashMap<>();
        for (Scenario.ProductSpec product : scenario.products()) {
            legacyDemand.put(product.code(), ONE);
        }

        List<String> active = new ArrayList<>();
        for (Resolved event : activeEvents(scenario, runId, round)) {
            active.add(event.spec().code());
            cost = cost.multiply(nz(event.costMultiplier()));
            wage = wage.multiply(nz(event.wageMultiplier()));

            // The pre-D82 mechanism: a card may still multiply the whole market, or one product.
            BigDecimal marketDemand = nz(event.demandMultiplier());
            if (marketDemand.compareTo(ONE) != 0) {
                if (event.targetProduct() == null || event.targetProduct().isBlank()) {
                    for (String code : size.keySet()) {
                        size.put(code, size.get(code).multiply(marketDemand));
                    }
                } else if (legacyDemand.containsKey(event.targetProduct())) {
                    legacyDemand.put(event.targetProduct(),
                            legacyDemand.get(event.targetProduct()).multiply(marketDemand));
                }
            }

            // D82: the card's actual reach — two numbers per customer class.
            for (Scenario.EventEffect effect : event.effects()) {
                if (size.containsKey(effect.segmentCode())) {
                    size.put(effect.segmentCode(), size.get(effect.segmentCode())
                            .multiply(nz(effect.sizeMultiplier())));
                    power.put(effect.segmentCode(), power.get(effect.segmentCode())
                            .multiply(nz(effect.purchasingPowerMultiplier())));
                }
            }
        }

        Map<String, SegmentState> segments = new LinkedHashMap<>();
        for (Scenario.SegmentSpec segment : scenario.segments()) {
            BigDecimal baseSize = nz(segment.baseSize());
            BigDecimal multiplier = baseSize.signum() == 0 ? ONE
                    : size.get(segment.code()).divide(baseSize, 6, RoundingMode.HALF_UP);
            segments.put(segment.code(), new SegmentState(segment.code(), segment.name(),
                    multiplier.setScale(6, RoundingMode.HALF_UP),
                    power.get(segment.code()).setScale(6, RoundingMode.HALF_UP)));
        }

        // The published demand multiplier per product, from the class it serves where there is one.
        Map<String, BigDecimal> byProduct = new LinkedHashMap<>();
        for (Scenario.ProductSpec product : scenario.products()) {
            BigDecimal multiplier;
            Optional<Scenario.SegmentSpec> segment = scenario.segmentOf(product);
            if (segment.isPresent()) {
                SegmentState state = segments.get(segment.get().code());
                BigDecimal basePower = nz(segment.get().basePurchasingPower());
                multiplier = state == null ? ONE
                        : state.sizeIndex().multiply(state.purchasingPower())
                                .divide(basePower, 6, RoundingMode.HALF_UP);
            } else {
                double elastic = Math.pow(demand.doubleValue(),
                        nz(product.incomeElasticity()).doubleValue());
                BigDecimal trendFactor = ONE.add(trend.multiply(nz(product.trendAffinity())));
                multiplier = BigDecimal.valueOf(elastic).multiply(trendFactor)
                        .multiply(legacyDemand.getOrDefault(product.code(), ONE));
            }
            byProduct.put(product.code(), multiplier.setScale(6, RoundingMode.HALF_UP));
        }

        return new RoundEnvironment(round, demand.setScale(3, RoundingMode.HALF_UP),
                cost.setScale(3, RoundingMode.HALF_UP), wage.setScale(3, RoundingMode.HALF_UP),
                trend.setScale(2, RoundingMode.HALF_UP), base.trendLabel(), segments, byProduct,
                List.copyOf(active));
    }

    /** Descriptions of the events that have already started — public knowledge by then. */
    public List<Map<String, Object>> activeEventDetails(Scenario scenario, long runId, int round,
                                                        String lang) {
        List<Map<String, Object>> details = new ArrayList<>();
        for (Resolved event : activeEvents(scenario, runId, round)) {
            Map<String, Object> row = new LinkedHashMap<>();
            // D86: the frozen row points at the card of this run's language, so its words are the
            // class's words; there is nothing to translate at read time.
            row.put("code", event.spec().code());
            row.put("name", event.spec().name());
            row.put("description", event.spec().description());
            row.put("category", event.spec().category());
            row.put("startedRound", event.startsRound());
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
                    .or(() -> templateAt(scenario, runLang(runId), Math.max(1, target)))
                    .orElse(new Scenario.RoundSpec(runLang(runId), target, ONE, ONE, ONE,
                            BigDecimal.ZERO, "Stable", "neutral", null));

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

    /**
     * The market environment a player reads in the workbench (D83): the round's narrative, the
     * classes with their current size and purchasing power, and the events that are already known.
     */
    public Map<String, Object> conditions(Scenario scenario, long runId, int round, String lang) {
        RoundEnvironment environment = forRound(scenario, runId, round);
        boolean zh = "zh".equals(lang);
        Scenario.RoundSpec frozen = runRound(runId, round).orElse(null);
        Scenario.RoundSpec template = templateAt(scenario, lang, Math.max(1, round)).orElse(null);
        // The run froze its words with its numbers (D84). A run drawn before that has none, so the
        // narrative falls back to the template position it was drawn from.
        Scenario.RoundSpec words = frozen != null && frozen.description() != null ? frozen : template;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("round", round);
        body.put("demandIndex", environment.demandIndex());
        body.put("costIndex", environment.costIndex());
        body.put("wageIndex", environment.wageIndex());
        body.put("trendStrength", environment.trendStrength());
        body.put("trendLabel", environment.trendLabel());
        body.put("outlook", outlook(environment.demandIndex()));
        List<Map<String, Object>> events = activeEventDetails(scenario, runId, round, lang);
        body.put("events", events);

        List<Map<String, Object>> segments = new ArrayList<>();
        Map<String, String> productOf = new LinkedHashMap<>();
        for (Scenario.ProductSpec product : scenario.products()) {
            if (product.targetSegment() != null) {
                productOf.put(product.targetSegment(), product.code());
            }
        }
        for (Scenario.SegmentSpec segment : scenario.segments()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", segment.code());
            row.put("name", zh ? orElse(segment.nameZh(), segment.name()) : segment.name());
            row.put("product", productOf.get(segment.code()));
            row.put("baseSize", nz(segment.baseSize()));
            row.put("sizeIndex", environment.segmentSize(segment.code()));
            row.put("purchasingPower", environment.segmentPurchasingPower(segment.code()));
            row.put("description", zh ? orElse(segment.descriptionZh(), segment.description())
                    : segment.description());
            segments.add(row);
        }
        body.put("segments", segments);
        body.put("headline", environment.trendLabel());
        body.put("description", marketDescription(words, events));
        return body;
    }

    /** The brief narrative: what the economy is doing this month, in the run's language. */
    private static String marketDescription(Scenario.RoundSpec base, List<Map<String, Object>> events) {
        StringBuilder text = new StringBuilder();
        if (base != null) {
            String roundText = base.description();
            if (roundText != null && !roundText.isBlank()) {
                text.append(roundText.trim());
            }
        }
        for (Map<String, Object> event : events) {
            Object description = event.get("description");
            if (description != null && !String.valueOf(description).isBlank()) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(String.valueOf(description).trim());
            }
        }
        return text.length() == 0 ? null : text.toString();
    }

    // ------------------------------------------------------------------ internals

    private Optional<Scenario.RoundSpec> runRound(long runId, int round) {
        return jdbc.query("""
                select demand_index, cost_index, wage_index, trend_strength, trend_label, description
                from run_round_economy where run_id = ? and round_number = ?
                """, (rs, i) -> new Scenario.RoundSpec(runLang(runId), round,
                rs.getBigDecimal("demand_index"), rs.getBigDecimal("cost_index"),
                rs.getBigDecimal("wage_index"), rs.getBigDecimal("trend_strength"),
                rs.getString("trend_label"), null, rs.getString("description")),
                runId, round).stream().findFirst();
    }

    /** The language this run reads the environment in (D64, D86); 'en' when the run is unknown. */
    String runLang(long runId) {
        return jdbc.query("select lang from run where id = ?",
                        (rs, i) -> rs.getString(1), runId)
                .stream().findFirst().orElse("en");
    }

    private static Optional<Scenario.RoundSpec> templateAt(Scenario scenario, String lang, int position) {
        return scenario.roundAt(lang, position);
    }

    /**
     * The draw, read from the run. A row written before D84 carries the old market-wide multipliers
     * and no effects, so an old run keeps the behaviour it was drawn under.
     */
    private List<Resolved> activeEvents(Scenario scenario, long runId, int round) {
        Map<String, Scenario.EventSpec> specs = new LinkedHashMap<>();
        for (Scenario.EventSpec spec : scenario.events()) {
            specs.put(spec.code(), spec);
        }
        List<Resolved> resolved = new ArrayList<>();
        jdbc.query("""
                select event_code, starts_round, demand_multiplier, cost_multiplier, wage_multiplier,
                       target_product, effects::text as effects
                from run_environment_event
                where run_id = ? and ? between starts_round and starts_round + duration_months - 1
                order by starts_round, event_code
                """, rs -> {
            Scenario.EventSpec spec = specs.get(rs.getString("event_code"));
            if (spec == null) {
                return;
            }
            resolved.add(new Resolved(spec, rs.getInt("starts_round"),
                    rs.getBigDecimal("demand_multiplier"), rs.getBigDecimal("cost_multiplier"),
                    rs.getBigDecimal("wage_multiplier"), rs.getString("target_product"),
                    readEffects(rs.getString("effects"))));
        }, runId, round);
        return resolved;
    }

    private List<Scenario.EventEffect> readEffects(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = json.readValue(raw, new TypeReference<>() {
            });
            List<Scenario.EventEffect> effects = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                effects.add(new Scenario.EventEffect(String.valueOf(row.get("segmentCode")),
                        new BigDecimal(String.valueOf(row.get("sizeMultiplier"))),
                        new BigDecimal(String.valueOf(row.get("purchasingPowerMultiplier")))));
            }
            return effects;
        } catch (Exception e) {
            throw new IllegalStateException("run " + " has unreadable frozen event effects", e);
        }
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

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? ONE : value;
    }
}
