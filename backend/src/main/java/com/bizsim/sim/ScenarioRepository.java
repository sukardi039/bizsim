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
                d.path("guidedRounds").asInt(0));


        List<Scenario.ProductSpec> products = jdbc.query("""
                select code, name, base_price, base_unit_cost, base_quality, base_demand, units_per_worker,
                       income_elasticity, trend_affinity
                from product where scenario_key = ? order by code
                """, (rs, i) -> new Scenario.ProductSpec(
                        rs.getString("code"), rs.getString("name"), rs.getBigDecimal("base_price"),
                        rs.getBigDecimal("base_unit_cost"), rs.getBigDecimal("base_quality"),
                        rs.getBigDecimal("base_demand"), rs.getBigDecimal("units_per_worker"),
                        rs.getBigDecimal("income_elasticity"), rs.getBigDecimal("trend_affinity")), key);

        List<Scenario.ChannelSpec> channels = jdbc.query("""
                select code, name, reach_weight, brand_alpha from channel
                where scenario_key = ? order by code
                """, (rs, i) -> new Scenario.ChannelSpec(
                        rs.getString("code"), rs.getString("name"), rs.getBigDecimal("reach_weight"),
                        rs.getBigDecimal("brand_alpha")), key);

        List<Scenario.ProjectSpec> projects = jdbc.query("""
                select code, name, coalesce(name_zh, name) as name_zh, target_product,
                       advantage_type, advantage_value, hidden_cost,
                       hint_band, duration_months, partial_benefit, benefit_curve, benefit_exponent,
                       overfunding
                from rnd_project where scenario_key = ? order by code
                """, (rs, i) -> new Scenario.ProjectSpec(
                        rs.getString("code"), rs.getString("name"), rs.getString("name_zh"),
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
                select round_number, demand_index, cost_index, wage_index, trend_strength,
                       trend_label, note
                from scenario_round where scenario_key = ? order by round_number
                """, (rs, i) -> new Scenario.RoundSpec(
                        rs.getInt("round_number"), rs.getBigDecimal("demand_index"),
                        rs.getBigDecimal("cost_index"), rs.getBigDecimal("wage_index"),
                        rs.getBigDecimal("trend_strength"), rs.getString("trend_label"),
                        rs.getString("note")), key);

        return new Scenario(key, params, products, channels, projects, rounds, eventsOf(key));
    }

    /** The event deck a builder may edit: the same shape the engine draws from (D18). */
    public List<Scenario.EventSpec> eventsOf(String key) {
        return jdbc.query("""
                select code, name, description, earliest_round, latest_round, probability,
                       duration_months, demand_multiplier, cost_multiplier, wage_multiplier,
                       target_product
                from environment_event where scenario_key = ? order by code
                """, (rs, i) -> new Scenario.EventSpec(
                        rs.getString("code"), rs.getString("name"), rs.getString("description"),
                        rs.getInt("earliest_round"), rs.getInt("latest_round"),
                        rs.getBigDecimal("probability"), rs.getInt("duration_months"),
                        rs.getBigDecimal("demand_multiplier"), rs.getBigDecimal("cost_multiplier"),
                        rs.getBigDecimal("wage_multiplier"), rs.getString("target_product")), key);
    }

    /** Replaces the whole deck: an event set with a gap or a duplicate code is not a set. */
    @Transactional
    public void saveEvents(String key, List<Scenario.EventSpec> events) {
        jdbc.update("delete from environment_event where scenario_key = ?", key);
        if (events == null) {
            return;
        }
        for (Scenario.EventSpec event : events) {
            jdbc.update("""
                    insert into environment_event (scenario_key, code, name, description, earliest_round,
                                                   latest_round, probability, duration_months,
                                                   demand_multiplier, cost_multiplier, wage_multiplier,
                                                   target_product)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, key, event.code(), event.name(), event.description(), event.earliestRound(),
                    event.latestRound(), event.probability(), event.durationMonths(),
                    event.demandMultiplier(), event.costMultiplier(), event.wageMultiplier(),
                    event.targetProduct());
        }
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
                where (s.tenant_id is null or s.tenant_id = ?) and s.frozen_for_package is null
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
                                     base_demand, units_per_worker, income_elasticity, trend_affinity)
                select ?, code, name, base_price, base_unit_cost, base_quality, base_demand,
                       units_per_worker, income_elasticity, trend_affinity
                from product where scenario_key = ?
                """, key, sourceKey);
        jdbc.update("""
                insert into channel (scenario_key, code, name, reach_weight, brand_alpha)
                select ?, code, name, reach_weight, brand_alpha from channel where scenario_key = ?
                """, key, sourceKey);
        jdbc.update("""
                insert into rnd_project (scenario_key, code, name, name_zh, target_product,
                                         advantage_type, advantage_value, hidden_cost, hint_band,
                                         duration_months, partial_benefit, benefit_curve,
                                         benefit_exponent, overfunding)
                select ?, code, name, name_zh, target_product, advantage_type, advantage_value,
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
                insert into environment_event (scenario_key, code, name, description, earliest_round,
                                               latest_round, probability, duration_months,
                                               demand_multiplier, cost_multiplier, wage_multiplier,
                                               target_product)
                select ?, code, name, description, earliest_round, latest_round, probability,
                       duration_months, demand_multiplier, cost_multiplier, wage_multiplier,
                       target_product
                from environment_event where scenario_key = ?
                """, key, sourceKey);

    }

    // ------------------------------------------------------------------ template editing (S6c)

    /** One position of the environment timeline, as the editor sees it. */
    public record RoundRow(int round, BigDecimal demandIndex, BigDecimal costIndex, BigDecimal wageIndex,
                           BigDecimal trendStrength, String trendLabel, String note) {
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
            "guidedRounds");

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
                select round_number, demand_index, cost_index, wage_index, trend_strength, trend_label, note
                from scenario_round where scenario_key = ? order by round_number
                """, (rs, i) -> new RoundRow(rs.getInt(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getString(6), rs.getString(7)), key);
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
        for (RoundRow row : rows) {
            jdbc.update("""
                    insert into scenario_round (scenario_key, round_number, demand_index, cost_index,
                                                wage_index, trend_strength, trend_label, note)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, key, row.round(), row.demandIndex(), row.costIndex(), row.wageIndex(),
                    row.trendStrength(), row.trendLabel(), row.note());
        }
        jdbc.update("update scenario set version = version + 1 where key = ?", key);
    }

    // ------------------------------------------------------------------ catalogue (S6d)

    public record ProductRow(String code, String name, BigDecimal basePrice, BigDecimal baseUnitCost,
                             BigDecimal baseQuality, BigDecimal baseDemand, BigDecimal unitsPerWorker,
                             BigDecimal incomeElasticity, BigDecimal trendAffinity) {
    }

    public record ChannelRow(String code, String name, BigDecimal reachWeight, BigDecimal brandAlpha) {
    }

    public record ProjectRow(String code, String name, String nameZh, String targetProduct,
                             String advantageType,
                             BigDecimal advantageValue, BigDecimal hiddenCost, BigDecimal hintBand,
                             int durationMonths, boolean partialBenefit, String benefitCurve,
                             BigDecimal benefitExponent, String overfunding) {
    }

    public List<ProductRow> products(String key) {
        return jdbc.query("""
                select code, name, base_price, base_unit_cost, base_quality, base_demand, units_per_worker,
                       income_elasticity, trend_affinity
                from product where scenario_key = ? order by code
                """, (rs, i) -> new ProductRow(rs.getString("code"), rs.getString("name"),
                rs.getBigDecimal("base_price"), rs.getBigDecimal("base_unit_cost"),
                rs.getBigDecimal("base_quality"), rs.getBigDecimal("base_demand"),
                rs.getBigDecimal("units_per_worker"), rs.getBigDecimal("income_elasticity"),
                rs.getBigDecimal("trend_affinity")), key);
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
                select code, name, coalesce(name_zh, name) as name_zh, target_product, advantage_type,
                       advantage_value, hidden_cost, hint_band, duration_months, partial_benefit,
                       benefit_curve, benefit_exponent, overfunding
                from rnd_project where scenario_key = ? order by code
                """, (rs, i) -> new ProjectRow(rs.getString("code"), rs.getString("name"),
                rs.getString("name_zh"),
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
                                         base_demand, units_per_worker, income_elasticity, trend_affinity)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, key, row.code(), row.name(), row.basePrice(), row.baseUnitCost(),
                    row.baseQuality(), row.baseDemand(), row.unitsPerWorker(), row.incomeElasticity(),
                    row.trendAffinity());
        }
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
    /**
     * Replaces the catalogue. `name_zh` is preserved when the caller sends none (D63): a client that
     * edits the English name should not silently erase the Chinese label, which is a translation, not
     * a mechanic.
     */
    public void saveProjects(String key, List<ProjectRow> rows) {
        java.util.Map<String, String> zhBefore = new java.util.HashMap<>();
        jdbc.query("select code, name_zh from rnd_project where scenario_key = ? and name_zh is not null",
                rs -> {
                    zhBefore.put(rs.getString(1), rs.getString(2));
                }, key);
        jdbc.update("delete from rnd_project where scenario_key = ?", key);
        for (ProjectRow row : rows) {
            String zh = row.nameZh() == null || row.nameZh().isBlank()
                    ? zhBefore.get(row.code()) : row.nameZh();
            jdbc.update("""
                    insert into rnd_project (scenario_key, code, name, name_zh, target_product,
                                             advantage_type, advantage_value, hidden_cost, hint_band,
                                             duration_months, partial_benefit, benefit_curve,
                                             benefit_exponent, overfunding)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, key, row.code(), row.name(), zh, row.targetProduct(), row.advantageType(),
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
