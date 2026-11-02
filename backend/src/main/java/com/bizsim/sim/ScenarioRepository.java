package com.bizsim.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown scenario " + key));

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
                salaryMap, staffMap);

        List<Scenario.ProductSpec> products = jdbc.query("""
                select code, name, base_price, base_unit_cost, base_quality, base_demand, units_per_worker
                from product where scenario_key = ? order by code
                """, (rs, i) -> new Scenario.ProductSpec(
                        rs.getString("code"), rs.getString("name"), rs.getBigDecimal("base_price"),
                        rs.getBigDecimal("base_unit_cost"), rs.getBigDecimal("base_quality"),
                        rs.getBigDecimal("base_demand"), rs.getBigDecimal("units_per_worker")), key);

        List<Scenario.ChannelSpec> channels = jdbc.query("""
                select code, name, reach_weight, brand_alpha from channel
                where scenario_key = ? order by code
                """, (rs, i) -> new Scenario.ChannelSpec(
                        rs.getString("code"), rs.getString("name"), rs.getBigDecimal("reach_weight"),
                        rs.getBigDecimal("brand_alpha")), key);

        List<Scenario.ProjectSpec> projects = jdbc.query("""
                select code, name, target_product, advantage_type, advantage_value, hidden_cost,
                       hint_band, duration_months
                from rnd_project where scenario_key = ? order by code
                """, (rs, i) -> new Scenario.ProjectSpec(
                        rs.getString("code"), rs.getString("name"), rs.getString("target_product"),
                        rs.getString("advantage_type"), rs.getBigDecimal("advantage_value"),
                        rs.getBigDecimal("hidden_cost"), rs.getBigDecimal("hint_band"),
                        rs.getInt("duration_months")), key);

        if (products.isEmpty()) {
            throw new IllegalStateException("scenario " + key + " has no products");
        }
        return new Scenario(key, params, products, channels, projects);
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
