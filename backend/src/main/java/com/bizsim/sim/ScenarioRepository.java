package com.bizsim.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import com.bizsim.web.ProblemException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads a scenario from the database: the definition JSON plus the product/channel/project catalogue.
 *
 * <p>Scenarios are read-only in v1 (decision E), so a load per round close is cheap enough and avoids
 * a cache that could serve a stale definition after a seed change.
 */
@Repository
public class ScenarioRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ScenarioRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Scenario load(String key) {
        String definition = jdbc.query(
                "select definition::text from scenario where key = ?",
                (rs, i) -> rs.getString(1), key).stream().findFirst().orElseThrow(
                () -> ProblemException.badRequest("template.unknown", "unknown scenario " + key, "template", key));

        JsonNode d;
        try {
            d = json.readTree(definition);
        } catch (Exception e) {
            throw new IllegalStateException("scenario " + key + " has unreadable definition", e);
        }

        JsonNode elasticity = d.path("elasticity");
        JsonNode baseline = d.path("baselineCompetitor");
        JsonNode salaries = d.path("salaries");
        JsonNode staff = d.path("startingStaff");

        Map<String, BigDecimal> salaryMap = new LinkedHashMap<>();
        salaries.fields().forEachRemaining(e -> salaryMap.put(e.getKey(), dec(e.getValue())));
        Map<String, Integer> staffMap = new LinkedHashMap<>();
        staff.fields().forEachRemaining(e -> staffMap.put(e.getKey(), e.getValue().asInt()));

        List<BigDecimal> seasonality = new ArrayList<>();
        d.path("seasonality").forEach(n -> seasonality.add(dec(n)));

        Scenario.Params params = new Scenario.Params(
                d.path("rounds").asInt(8),
                d.path("roundMinutes").asInt(20),
                dec(d.path("startingCash")),
                dec(d.path("startingBrand")),
                dec(d.path("fixedCostPerMonth")),
                dec(d.path("brandDecay")),
                dec(d.path("holdingCostPerUnit")),
                dec(d.path("referenceQuality")),
                dec(d.path("referenceSpend")),
                dec(d.path("machineCostPerWorker")),
                dec(d.path("hiringCostMonths")),
                dec(d.path("severanceMonths")),
                dec(d.path("minRndBenefitRatio")),
                dec(baseline.path("share")),
                seasonality,
                dec(elasticity.path("price")),
                dec(elasticity.path("quality")),
                dec(elasticity.path("brand")),
                dec(elasticity.path("marketing")),
                salaryMap, staffMap,
                dec(d.path("marketReportCost")),
                d.path("forecastHorizon").asInt(2),
                dec(d.path("forecastBand")),
                d.path("cycles").asInt(2),
                d.path("cycleLength").asInt(4),
                dec(d.path("cycleDemandDrift")),
                dec(d.path("cycleCostDrift")),
                // Absent means off: a template written before the mechanic existed keeps its
                // behaviour exactly, which is what "default = current behaviour" means.
                d.path("qualityUpgrade").asBoolean(false),
                // A template that turns the switch on without naming a price would sell quality for
                // nothing; the loader supplies the shipped rate instead of failing at round close.
                dec(d.path("qualityCostPerPoint")).signum() > 0
                        ? dec(d.path("qualityCostPerPoint")) : new BigDecimal("60000000"),
                // D48: absent means off, so an existing package seats the same companies it always did.
                d.path("autoWorkers").asBoolean(false),
                d.path("guidedRounds").asInt(0),
                "WEIGHTED".equalsIgnoreCase(d.path("costBasis").asText("REPLACEMENT")));


        List<Scenario.ProductSpec> products = jdbc.query("""
                select code, name, base_price, base_unit_cost, base_quality, base_demand, units_per_worker,
                       income_elasticity, trend_affinity, target_segment, cross_segment_pct
                from product where scenario_key = ? order by code
                """, (rs, i) -> new Scenario.ProductSpec(
                        rs.getString("code"), rs.getString("name"), rs.getBigDecimal("base_price"),
                        rs.getBigDecimal("base_unit_cost"), rs.getBigDecimal("base_quality"),
                        rs.getBigDecimal("base_demand"), rs.getBigDecimal("units_per_worker"),
                        rs.getBigDecimal("income_elasticity"), rs.getBigDecimal("trend_affinity"),
                        rs.getString("target_segment"), rs.getBigDecimal("cross_segment_pct")), key);

        List<Scenario.ChannelSpec> channels = jdbc.query("""
                select code, name, reach_weight, brand_alpha from channel
                where scenario_key = ? order by code
                """, (rs, i) -> new Scenario.ChannelSpec(
                        rs.getString("code"), rs.getString("name"), rs.getBigDecimal("reach_weight"),
                        rs.getBigDecimal("brand_alpha")), key);

        List<Scenario.ProjectSpec> projects = jdbc.query("""
                select code, name, lang, target_product,
                       advantage_type, advantage_value, hidden_cost,
                       hint_band, duration_months, partial_benefit, benefit_curve, benefit_exponent,
                       overfunding
                from rnd_project where scenario_key = ? order by lang, code
                """, (rs, i) -> new Scenario.ProjectSpec(
                        rs.getString("code"), rs.getString("name"), rs.getString("lang"),
                        rs.getString("target_product"),
                        rs.getString("advantage_type"), rs.getBigDecimal("advantage_value"),
                        rs.getBigDecimal("hidden_cost"), rs.getBigDecimal("hint_band"),
                        rs.getInt("duration_months"), rs.getBoolean("partial_benefit"),
                        rs.getString("benefit_curve"), rs.getBigDecimal("benefit_exponent"),
                        rs.getString("overfunding")), key);

        if (products.isEmpty()) {
            throw new IllegalStateException("scenario " + key + " has no products");
        }

        List<Scenario.RoundSpec> rounds = jdbc.query("""
                select r.round_number, r.demand_index, r.cost_index, r.wage_index, r.trend_strength,
                       r.trend_label, r.note,
                       (select t.description from environment_text t
                        where t.scenario_key = r.scenario_key and t.ref_type = 'ROUND'
                          and t.ref_code = r.round_number::text and t.lang = 'en') as description,
                       (select t.description from environment_text t
                        where t.scenario_key = r.scenario_key and t.ref_type = 'ROUND'
                          and t.ref_code = r.round_number::text and t.lang = 'zh') as description_zh,
                       (select t.name from environment_text t
                        where t.scenario_key = r.scenario_key and t.ref_type = 'ROUND'
                          and t.ref_code = r.round_number::text and t.lang = 'zh') as label_zh
                from scenario_round r where r.scenario_key = ? order by r.round_number
                """, (rs, i) -> new Scenario.RoundSpec(
                        rs.getInt("round_number"), rs.getBigDecimal("demand_index"),
                        rs.getBigDecimal("cost_index"), rs.getBigDecimal("wage_index"),
                        rs.getBigDecimal("trend_strength"), rs.getString("trend_label"),
                        rs.getString("note"), rs.getString("description"),
                        rs.getString("description_zh"), rs.getString("label_zh")), key);

        return new Scenario(key, params, products, channels, projects, rounds, segmentsOf(key), eventsOf(key));
    }

    /**
     * The customer classes of a template (D81). Elasticity and trend affinity live here, not on the
     * product: the class is what an economy moves, and a product inherits its class's weather.
     */
    public List<Scenario.SegmentSpec> segmentsOf(String key) {
        return jdbc.query("""
                select s.code, s.name, s.base_size, s.base_purchasing_power, s.income_elasticity,
                       s.trend_affinity, s.sort_order,
                       (select t.name from environment_text t where t.scenario_key = s.scenario_key
                         and t.ref_type = 'SEGMENT' and t.ref_code = s.code and t.lang = 'en') as name_en,
                       (select t.description from environment_text t where t.scenario_key = s.scenario_key
                         and t.ref_type = 'SEGMENT' and t.ref_code = s.code and t.lang = 'en') as description_en,
                       (select t.name from environment_text t where t.scenario_key = s.scenario_key
                         and t.ref_type = 'SEGMENT' and t.ref_code = s.code and t.lang = 'zh') as name_zh,
                       (select t.description from environment_text t where t.scenario_key = s.scenario_key
                         and t.ref_type = 'SEGMENT' and t.ref_code = s.code and t.lang = 'zh') as description_zh
                from customer_segment s where s.scenario_key = ? order by s.sort_order, s.code
                """, (rs, i) -> new Scenario.SegmentSpec(
                        rs.getString("code"),
                        orElse(rs.getString("name_en"), rs.getString("name")),
                        rs.getString("name_zh"), rs.getString("description_en"),
                        rs.getString("description_zh"), rs.getBigDecimal("base_size"),
                        rs.getBigDecimal("base_purchasing_power"),
                        rs.getBigDecimal("income_elasticity"), rs.getBigDecimal("trend_affinity"),
                        rs.getInt("sort_order")), key);
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** The event deck a builder may edit: the same shape the engine draws from (D18). */
    public List<Scenario.EventSpec> eventsOf(String key) {
        Map<String, List<Scenario.EventEffect>> effects = effectsOf(key);
        Map<String, String[]> words = wordsOf(key, "EVENT");
        return jdbc.query("""
                select code, name, description, category, earliest_round, latest_round, probability,
                       duration_months, demand_multiplier, cost_multiplier, wage_multiplier,
                       target_product
                from environment_event where scenario_key = ? order by code
                """, (rs, i) -> {
                    String code = rs.getString("code");
                    String[] zh = words.getOrDefault(code, new String[2]);
                    return new Scenario.EventSpec(
                            code, rs.getString("name"), rs.getString("description"),
                            rs.getString("category"), rs.getInt("earliest_round"),
                            rs.getInt("latest_round"), rs.getBigDecimal("probability"),
                            rs.getInt("duration_months"), rs.getBigDecimal("demand_multiplier"),
                            rs.getBigDecimal("cost_multiplier"), rs.getBigDecimal("wage_multiplier"),
                            rs.getString("target_product"), zh[0], zh[1],
                            effects.getOrDefault(code, List.of()));
                }, key);
    }

    /** Effects of every card, keyed by event code. */
    private Map<String, List<Scenario.EventEffect>> effectsOf(String key) {
        Map<String, List<Scenario.EventEffect>> byEvent = new LinkedHashMap<>();
        jdbc.query("""
                select event_code, segment_code, size_multiplier, purchasing_power_multiplier
                from environment_event_effect where scenario_key = ? order by event_code, segment_code
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        byEvent.computeIfAbsent(rs.getString("event_code"), k -> new ArrayList<>())
                                .add(new Scenario.EventEffect(rs.getString("segment_code"),
                                        rs.getBigDecimal("size_multiplier"),
                                        rs.getBigDecimal("purchasing_power_multiplier"))), key);
        return byEvent;
    }

    /** The Chinese {name, description} of every text row of one kind, keyed by ref_code. */
    private Map<String, String[]> wordsOf(String key, String refType) {
        Map<String, String[]> words = new LinkedHashMap<>();
        jdbc.query("""
                select ref_code, name, description from environment_text
                where scenario_key = ? and ref_type = ? and lang = 'zh'
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        words.put(rs.getString("ref_code"),
                                new String[]{rs.getString("name"), rs.getString("description")}),
                key, refType);
        return words;
    }

    /**
     * Replaces the whole deck: an event set with a gap or a duplicate code is not a set.
     *
     * <p>Effects and the Chinese words go with the card. Three tables, one transaction: a card saved
     * without its effects would fire and do nothing, which is worse than refusing the edit.
     */
    @Transactional
    public void saveEvents(String key, List<Scenario.EventSpec> events) {
        jdbc.update("""
                delete from environment_text where scenario_key = ? and ref_type = 'EVENT'
                """, key);
        jdbc.update("delete from environment_event where scenario_key = ?", key);
        if (events == null) {
            return;
        }
        for (Scenario.EventSpec event : events) {
            jdbc.update("""
                    insert into environment_event (scenario_key, code, name, description, category,
                                                   earliest_round, latest_round, probability,
                                                   duration_months, demand_multiplier, cost_multiplier,
                                                   wage_multiplier, target_product)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, key, event.code(), event.name(), event.description(),
                    category(event.category()), event.earliestRound(), event.latestRound(),
                    event.probability(), event.durationMonths(), event.demandMultiplier(),
                    event.costMultiplier(), event.wageMultiplier(), event.targetProduct());
            for (Scenario.EventEffect effect : event.effects() == null ? List.<Scenario.EventEffect>of()
                    : event.effects()) {
                jdbc.update("""
                        insert into environment_event_effect (scenario_key, event_code, segment_code,
                                                              size_multiplier,
                                                              purchasing_power_multiplier)
                        values (?, ?, ?, ?, ?)
                        """, key, event.code(), effect.segmentCode(), effect.sizeMultiplier(),
                        effect.purchasingPowerMultiplier());
            }
            saveWords(key, "EVENT", event.code(), event.nameZh(), event.descriptionZh());
        }
        jdbc.update("update scenario set version = version + 1 where key = ?", key);
    }

    /** Only the categories the workbench knows how to print; anything else is a plain shock. */
    private static String category(String value) {
        return value != null && EVENT_CATEGORIES.contains(value) ? value : "OTHER";
    }

    public static final List<String> EVENT_CATEGORIES = List.of("SUPPLY_SHOCK", "FINANCIAL_CRISIS",
            "INFLATION", "TECHNOLOGY", "DEMAND_SHOCK", "POLICY", "OTHER");

    /** Upserts one language's words for one object. A blank value deletes the row rather than storing "". */
    private void saveWords(String key, String refType, String refCode, String name, String description) {
        jdbc.update("""
                delete from environment_text where scenario_key = ? and ref_type = ? and ref_code = ?
                  and lang = 'zh'
                """, key, refType, refCode);
        if ((name == null || name.isBlank()) && (description == null || description.isBlank())) {
            return;
        }
        jdbc.update("""
                insert into environment_text (scenario_key, ref_type, ref_code, lang, name, description)
                values (?, ?, ?, 'zh', ?, ?)
                """, key, refType, refCode, name, description);
    }

    // ------------------------------------------------------------------ templates

    /** A template the caller can see: the shipped global one, or one their own tenant copied. */
    public record TemplateRow(String key, String name, Long tenantId, String status, String basedOn,
                              int version, int products, int rounds) {
    }

    public List<TemplateRow> listVisible(long tenantId) {
        return jdbc.query("""
                select s.key, s.name, s.tenant_id, s.status, s.based_on, s.version,
                       (select count(*) from product p where p.scenario_key = s.key) as products,
                       (select count(*) from scenario_round r where r.scenario_key = s.key) as rounds
                from scenario s
                where (s.tenant_id is null or s.tenant_id = ?)
                  and s.frozen_for_package is null
                  -- A frozen copy is named `pkg-<tenant>-<8 hex>` (PackageService.frozenKey). Normally
                  -- the back-reference above hides it, but a copy whose package was deleted before this
                  -- rule existed has no back-reference left and would read as an ordinary template —
                  -- which is how deleted packages' copies ended up in the package template picker.
                  and s.key !~ '^pkg-.+-[0-9a-f]{8}$'
                order by s.tenant_id nulls first, s.name
                """, (rs, i) -> new TemplateRow(
                rs.getString("key"), rs.getString("name"),
                (Long) rs.getObject("tenant_id"), rs.getString("status"), rs.getString("based_on"),
                rs.getInt("version"), rs.getInt("products"), rs.getInt("rounds")), tenantId);
    }

    /**
     * Whether a scenario exists for this tenant — including the frozen copies packages run on, which
     * are deliberately absent from the template library. Used by session start, not by the editor.
     */
    public boolean existsForTenant(long tenantId, String key) {
        Integer count = jdbc.queryForObject("""
                select count(*) from scenario
                where key = ? and (tenant_id is null or tenant_id = ?)
                """, Integer.class, key, tenantId);
        return count != null && count > 0;
    }

    public Optional<ScenarioRepository.TemplateRow> findVisible(long tenantId, String key) {
        return listVisible(tenantId).stream().filter(t -> t.key().equals(key)).findFirst();
    }

    /**
     * Copies a visible template into a tenant-owned DRAFT. The copy is a full scenario: the
     * definition JSON plus every catalogue row that carries {@code scenario_key}, so the tenant can
     * edit it without touching the global template and without needing a second lookup path.
     *
     * <p>The clone is deliberately a copy of the whole document, not a diff against the base — a
     * template that only overrides some fields would have to be merged at load time, and the merge
     * would become the thing classes depend on.
     */
    @Transactional
    public String copy(String sourceKey, long tenantId, String tenantCode, String requestedName) {
        TemplateRow source = findVisible(tenantId, sourceKey).orElseThrow(
                () -> ProblemException.notFound("template.not_found", "no such template"));

        String base = (tenantCode + "-" + sourceKey).toLowerCase().replaceAll("[^a-z0-9-]", "-");
        if (base.length() > 50) {
            base = base.substring(0, 50);
        }
        String key = base;
        for (int n = 2; jdbc.queryForObject("select count(*) from scenario where key = ?",
                Integer.class, key) > 0; n++) {
            key = base + "-" + n;
        }

        String name = requestedName == null || requestedName.isBlank()
                ? source.name() + " (copy)" : requestedName.trim();
        cloneInto(sourceKey, key, name, tenantId, "DRAFT");
        return key;
    }

    @Transactional
    public String freeze(String sourceKey, long tenantId, String frozenKey, String name) {
        // PUBLISHED, not DRAFT: a frozen copy is what a class runs on, not something to be edited.
        cloneInto(sourceKey, frozenKey, name, tenantId, "PUBLISHED");
        // D79: publishing is the last moment the economics can still be chosen, so a template that
        // does not name its inventory basis is frozen on weighted-average cost — the basis under
        // which cash movement equals profit plus stock change. A template that says REPLACEMENT keeps
        // it, and everything already published is untouched.
        jdbc.update("""
                update scenario set definition = jsonb_set(definition, '{costBasis}', '"WEIGHTED"')
                where key = ? and not jsonb_exists(definition, 'costBasis')
                """, frozenKey);
        return frozenKey;
    }

    /** Marks the frozen row as belonging to the package that produced it. */
    public void markFrozen(String key, long packageId) {
        jdbc.update("update scenario set frozen_for_package = ? where key = ?", packageId, key);
    }

    /** True for the private copies packages run on: invisible in the template library, never editable. */
    public boolean isFrozen(String key) {
        Boolean frozen = jdbc.queryForObject(
                "select frozen_for_package is not null from scenario where key = ?", Boolean.class, key);
        return Boolean.TRUE.equals(frozen);
    }

    /**
     * One clone routine, used by both "copy this template into my drafts" and "freeze this template
     * into a package". Same five catalogue tables either way — a copy that missed one would load with
     * no products and only fail at the first round.
     */
    @Transactional
    public void cloneInto(String sourceKey, String key, String name, long tenantId, String status) {
        jdbc.update("""
                insert into scenario (key, name, definition, version, tenant_id, status, based_on)
                select ?, ?, definition, version, ?, ?, key from scenario where key = ?
                """, key, name, tenantId, status, sourceKey);

        jdbc.update("""
                insert into product (scenario_key, code, name, base_price, base_unit_cost, base_quality,
                                     base_demand, units_per_worker, income_elasticity, trend_affinity,
                                     target_segment, cross_segment_pct)
                select ?, code, name, base_price, base_unit_cost, base_quality, base_demand,
                       units_per_worker, income_elasticity, trend_affinity, target_segment,
                       cross_segment_pct
                from product where scenario_key = ?
                """, key, sourceKey);
        jdbc.update("""
                insert into channel (scenario_key, code, name, reach_weight, brand_alpha)
                select ?, code, name, reach_weight, brand_alpha from channel where scenario_key = ?
                """, key, sourceKey);
        jdbc.update("""
                insert into rnd_project (scenario_key, code, name, lang, target_product,
                                         advantage_type, advantage_value, hidden_cost, hint_band,
                                         duration_months, partial_benefit, benefit_curve,
                                         benefit_exponent, overfunding)
                select ?, code, name, lang, target_product, advantage_type, advantage_value,
                       hidden_cost, hint_band, duration_months, partial_benefit, benefit_curve,
                       benefit_exponent, overfunding
                from rnd_project where scenario_key = ?
                """, key, sourceKey);
        jdbc.update("""
                insert into scenario_round (scenario_key, round_number, demand_index, cost_index,
                                            wage_index, note, trend_strength, trend_label)
                select ?, round_number, demand_index, cost_index, wage_index, note, trend_strength,
                       trend_label
                from scenario_round where scenario_key = ?
                """, key, sourceKey);
        jdbc.update("""
                insert into environment_event (scenario_key, code, name, description, category,
                                               earliest_round, latest_round, probability,
                                               duration_months, demand_multiplier, cost_multiplier,
                                               wage_multiplier, target_product)
                select ?, code, name, description, category, earliest_round, latest_round,
                       probability, duration_months, demand_multiplier, cost_multiplier,
                       wage_multiplier, target_product
                from environment_event where scenario_key = ?
                """, key, sourceKey);
        // D81/D82: the customer classes and each card's effect on them are catalogue data too. A
        // copy without them would load with no customers and every event doing nothing.
        jdbc.update("""
                insert into customer_segment (scenario_key, code, name, base_size,
                                              base_purchasing_power, income_elasticity,
                                              trend_affinity, sort_order)
                select ?, code, name, base_size, base_purchasing_power, income_elasticity,
                       trend_affinity, sort_order
                from customer_segment where scenario_key = ?
                """, key, sourceKey);
        jdbc.update("""
                insert into environment_event_effect (scenario_key, event_code, segment_code,
                                                      size_multiplier, purchasing_power_multiplier)
                select ?, event_code, segment_code, size_multiplier, purchasing_power_multiplier
                from environment_event_effect where scenario_key = ?
                """, key, sourceKey);
        jdbc.update("""
                insert into environment_text (scenario_key, ref_type, ref_code, lang, name, description)
                select ?, ref_type, ref_code, lang, name, description
                from environment_text where scenario_key = ?
                """, key, sourceKey);

    }

    // ------------------------------------------------------------------ template editing (S6c)

    /** One position of the environment timeline, as the editor sees it. */
    public record RoundRow(int round, BigDecimal demandIndex, BigDecimal costIndex, BigDecimal wageIndex,
                           BigDecimal trendStrength, String trendLabel, String note,
                           String description, String descriptionZh) {
    }

    /** The definition keys a facilitator may change. Everything else is engine calibration. */
    private static final List<String> EDITABLE_SETTINGS = List.of(
            "rounds", "roundMinutes", "startingCash", "startingBrand", "fixedCostPerMonth", "brandDecay",
            "holdingCostPerUnit", "referenceQuality", "referenceSpend", "machineCostPerWorker",
            "hiringCostMonths", "severanceMonths", "minRndBenefitRatio", "marketReportCost",
            "forecastHorizon", "forecastBand", "cycles", "cycleLength", "cycleDemandDrift",
            "cycleCostDrift", "salaries", "startingStaff",
            // D15/D26: the quality mechanic is a per-template switch; the price is a builder number.
            "qualityUpgrade", "qualityCostPerPoint", "autoWorkers",
            // D61: how many opening rounds a trainee sees a suggested plan in.
            "guidedRounds",
            // D79: REPLACEMENT or WEIGHTED inventory costing — a builder choice, not calibration.
            "costBasis");

    /** The editable part of the definition document, as stored. */
    public Map<String, Object> settings(String key) {
        String definition = jdbc.queryForObject(
                "select definition::text from scenario where key = ?", String.class, key);
        try {
            JsonNode node = json.readTree(definition);
            Map<String, Object> settings = new LinkedHashMap<>();
            for (String field : EDITABLE_SETTINGS) {
                JsonNode value = node.get(field);
                if (value != null) {
                    settings.put(field, json.convertValue(value, Object.class));
                }
            }
            return settings;
        } catch (Exception e) {
            throw new IllegalStateException("scenario " + key + " has unreadable definition", e);
        }
    }

    public List<RoundRow> environment(String key) {
        return jdbc.query("""
                select round_number, demand_index, cost_index, wage_index, trend_strength, trend_label, note,
                       (select t.description from environment_text t
                        where t.scenario_key = r.scenario_key and t.ref_type = 'ROUND'
                          and t.ref_code = r.round_number::text and t.lang = 'en') as description,
                       (select t.description from environment_text t
                        where t.scenario_key = r.scenario_key and t.ref_type = 'ROUND'
                          and t.ref_code = r.round_number::text and t.lang = 'zh') as description_zh
                from scenario_round r where r.scenario_key = ? order by r.round_number
                """, (rs, i) -> new RoundRow(rs.getInt(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9)), key);
    }

    /**
     * Merges the submitted settings into the definition document.
     *
     * <p>A merge, not a replacement: the document also holds calibration the editor never shows
     * (elasticities, baseline competitor), and writing the whole document from a form would silently
     * drop those. Unknown keys are ignored, so a stale client cannot invent engine calibration.
     */
    @Transactional
    public void saveSettings(String key, Map<String, Object> submitted) {
        String definition = jdbc.queryForObject(
                "select definition::text from scenario where key = ?", String.class, key);
        try {
            ObjectNode node = (ObjectNode) json.readTree(definition);
            for (String field : EDITABLE_SETTINGS) {
                if (submitted.containsKey(field)) {
                    node.set(field, json.valueToTree(submitted.get(field)));
                }
            }
            jdbc.update("update scenario set definition = ?::jsonb, version = version + 1 where key = ?",
                    json.writeValueAsString(node), key);
        } catch (Exception e) {
            throw new IllegalStateException("cannot merge settings for " + key, e);
        }
    }

    /** Replaces the whole environment timeline: the positions are a table, not a partial edit. */
    @Transactional
    public void saveEnvironment(String key, List<RoundRow> rows) {
        jdbc.update("delete from scenario_round where scenario_key = ?", key);
        jdbc.update("""
                delete from environment_text where scenario_key = ? and ref_type = 'ROUND'
                """, key);
        for (RoundRow row : rows) {
            jdbc.update("""
                    insert into scenario_round (scenario_key, round_number, demand_index, cost_index,
                                                wage_index, trend_strength, trend_label, note)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, key, row.round(), row.demandIndex(), row.costIndex(), row.wageIndex(),
                    row.trendStrength(), row.trendLabel(), row.note());
            // D83: the brief description a player reads. Both languages are written here so the
            // workbench can show the class's language without a second edit round.
            saveRoundWords(key, row, "en", row.trendLabel(), row.description());
            saveRoundWords(key, row, "zh", row.trendLabel(), row.descriptionZh());
        }
        jdbc.update("update scenario set version = version + 1 where key = ?", key);
    }

    private void saveRoundWords(String key, RoundRow row, String lang, String name, String description) {
        if ((description == null || description.isBlank())) {
            return;
        }
        jdbc.update("""
                insert into environment_text (scenario_key, ref_type, ref_code, lang, name, description)
                values (?, 'ROUND', ?, ?, ?, ?)
                """, key, String.valueOf(row.round()), lang, name, description);
    }

    // ------------------------------------------------------------------ catalogue (S6d)

    public record ProductRow(String code, String name, BigDecimal basePrice, BigDecimal baseUnitCost,
                             BigDecimal baseQuality, BigDecimal baseDemand, BigDecimal unitsPerWorker,
                             BigDecimal incomeElasticity, BigDecimal trendAffinity,
                             String targetSegment, BigDecimal crossSegmentPct) {
    }

    /** One customer class as the editor sees it (D81). */
    public record SegmentRow(String code, String name, String nameZh, String description,
                             String descriptionZh, BigDecimal baseSize,
                             BigDecimal basePurchasingPower, BigDecimal incomeElasticity,
                             BigDecimal trendAffinity, int sortOrder) {
    }

    public record ChannelRow(String code, String name, BigDecimal reachWeight, BigDecimal brandAlpha) {
    }

    public record ProjectRow(String code, String name, String lang, String targetProduct,
                             String advantageType,
                             BigDecimal advantageValue, BigDecimal hiddenCost, BigDecimal hintBand,
                             int durationMonths, boolean partialBenefit, String benefitCurve,
                             BigDecimal benefitExponent, String overfunding) {
    }

    public List<ProductRow> products(String key) {
        return jdbc.query("""
                select code, name, base_price, base_unit_cost, base_quality, base_demand, units_per_worker,
                       income_elasticity, trend_affinity, target_segment, cross_segment_pct
                from product where scenario_key = ? order by code
                """, (rs, i) -> new ProductRow(rs.getString("code"), rs.getString("name"),
                rs.getBigDecimal("base_price"), rs.getBigDecimal("base_unit_cost"),
                rs.getBigDecimal("base_quality"), rs.getBigDecimal("base_demand"),
                rs.getBigDecimal("units_per_worker"), rs.getBigDecimal("income_elasticity"),
                rs.getBigDecimal("trend_affinity"), rs.getString("target_segment"),
                rs.getBigDecimal("cross_segment_pct")), key);
    }

    /** The customer classes the editor may change: economy parameters, not catalogue codes. */
    public List<SegmentRow> segments(String key) {
        return segmentsOf(key).stream().map(s -> new SegmentRow(s.code(), s.name(), s.nameZh(),
                s.description(), s.descriptionZh(), s.baseSize(), s.basePurchasingPower(),
                s.incomeElasticity(), s.trendAffinity(), s.sortOrder())).toList();
    }

    public List<ChannelRow> channels(String key) {
        return jdbc.query("""
                select code, name, reach_weight, brand_alpha from channel
                where scenario_key = ? order by code
                """, (rs, i) -> new ChannelRow(rs.getString("code"), rs.getString("name"),
                rs.getBigDecimal("reach_weight"), rs.getBigDecimal("brand_alpha")), key);
    }

    public List<ProjectRow> projects(String key) {
        return jdbc.query("""
                select code, name, lang, target_product, advantage_type,
                       advantage_value, hidden_cost, hint_band, duration_months, partial_benefit,
                       benefit_curve, benefit_exponent, overfunding
                from rnd_project where scenario_key = ? order by lang, code
                """, (rs, i) -> new ProjectRow(rs.getString("code"), rs.getString("name"),
                rs.getString("lang"),
                rs.getString("target_product"), rs.getString("advantage_type"),
                rs.getBigDecimal("advantage_value"), rs.getBigDecimal("hidden_cost"),
                rs.getBigDecimal("hint_band"), rs.getInt("duration_months"),
                rs.getBoolean("partial_benefit"), rs.getString("benefit_curve"),
                rs.getBigDecimal("benefit_exponent"), rs.getString("overfunding")), key);
    }

    /**
     * Replaces the product catalogue. Like the environment, a catalogue is a set: a partial edit
     * leaves companies holding products the template no longer has.
     *
     * <p>This is the one edit that can strand a live class — a run's companies were created from
     * these codes and their decisions name them — so the service refuses when the template has runs
     * (see {@link #hasRuns}).
     */
    @Transactional
    public void saveProducts(String key, List<ProductRow> rows) {
        jdbc.update("delete from product where scenario_key = ?", key);
        for (ProductRow row : rows) {
            jdbc.update("""
                    insert into product (scenario_key, code, name, base_price, base_unit_cost, base_quality,
                                         base_demand, units_per_worker, income_elasticity, trend_affinity,
                                         target_segment, cross_segment_pct)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, key, row.code(), row.name(), row.basePrice(), row.baseUnitCost(),
                    row.baseQuality(), row.baseDemand(), row.unitsPerWorker(), row.incomeElasticity(),
                    row.trendAffinity(), row.targetSegment(),
                    row.crossSegmentPct() == null ? BigDecimal.ZERO : row.crossSegmentPct());
        }
        jdbc.update("update scenario set version = version + 1 where key = ?", key);
    }

    /**
     * Replaces the customer classes (D81). The class is the unit the economy moves, so a class edit
     * changes every round of every session on this template — the controller refuses it once the
     * template has runs, exactly like the product catalogue.
     */
    @Transactional
    public void saveSegments(String key, List<SegmentRow> rows) {
        // Never nulls a product's target: a class a product serves cannot be removed, and the
        // controller refuses that edit before reaching here. Removing the link here would silently
        // drop those products back to the legacy demand pool.
        jdbc.update("delete from customer_segment where scenario_key = ?", key);
        jdbc.update("""
                delete from environment_text where scenario_key = ? and ref_type = 'SEGMENT'
                """, key);
        if (rows == null) {
            jdbc.update("update scenario set version = version + 1 where key = ?", key);
            return;
        }
        int position = 0;
        for (SegmentRow row : rows) {
            jdbc.update("""
                    insert into customer_segment (scenario_key, code, name, base_size,
                                                  base_purchasing_power, income_elasticity,
                                                  trend_affinity, sort_order)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, key, row.code(), row.name(), row.baseSize(), row.basePurchasingPower(),
                    row.incomeElasticity(), row.trendAffinity(),
                    row.sortOrder() == 0 ? ++position : row.sortOrder());
            saveWords(key, "SEGMENT", row.code(), row.nameZh(), row.descriptionZh());
            if (row.description() != null && !row.description().isBlank()) {
                jdbc.update("""
                        insert into environment_text (scenario_key, ref_type, ref_code, lang, name,
                                                      description)
                        values (?, 'SEGMENT', ?, 'en', ?, ?)
                        on conflict (scenario_key, ref_type, ref_code, lang) do update
                            set name = excluded.name, description = excluded.description
                        """, key, row.code(), row.name(), row.description());
            }
        }
        // An effect pointing at a class that is gone would never fire and would confuse the editor.
        jdbc.update("""
                delete from environment_event_effect e where e.scenario_key = ?
                  and not exists (select 1 from customer_segment s
                                  where s.scenario_key = e.scenario_key and s.code = e.segment_code)
                """, key);
        jdbc.update("update scenario set version = version + 1 where key = ?", key);
    }

    @Transactional
    public void saveChannels(String key, List<ChannelRow> rows) {
        jdbc.update("delete from channel where scenario_key = ?", key);
        for (ChannelRow row : rows) {
            jdbc.update("""
                    insert into channel (scenario_key, code, name, reach_weight, brand_alpha)
                    values (?, ?, ?, ?, ?)
                    """, key, row.code(), row.name(), row.reachWeight(), row.brandAlpha());
        }
        jdbc.update("update scenario set version = version + 1 where key = ?", key);
    }

    @Transactional
    /** Replaces the catalogue. Each row carries its language, so both sets live here side by side. */
    public void saveProjects(String key, List<ProjectRow> rows) {
        jdbc.update("delete from rnd_project where scenario_key = ?", key);
        for (ProjectRow row : rows) {
            String lang = "zh".equalsIgnoreCase(row.lang()) ? "zh" : "en";
            jdbc.update("""
                    insert into rnd_project (scenario_key, code, name, lang, target_product,
                                             advantage_type, advantage_value, hidden_cost, hint_band,
                                             duration_months, partial_benefit, benefit_curve,
                                             benefit_exponent, overfunding)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, key, row.code(), row.name(), lang, row.targetProduct(), row.advantageType(),
                    row.advantageValue(), row.hiddenCost(), row.hintBand(), row.durationMonths(),
                    row.partialBenefit(), row.benefitCurve(), row.benefitExponent(), row.overfunding());
        }
        jdbc.update("update scenario set version = version + 1 where key = ?", key);
    }

    /** True once a session has been played on this template: catalogue edits would strand it. */
    public boolean hasRuns(long tenantId, String key) {
        return jdbc.queryForObject(
                "select count(*) from run where tenant_id = ? and scenario_key = ?",
                Integer.class, tenantId, key) > 0;
    }

    private static BigDecimal dec(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return BigDecimal.ZERO;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        return new BigDecimal(node.asText());
    }
}
