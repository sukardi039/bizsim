package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D106: a market is a count of items.
 *
 * <p>The columns said otherwise (`numeric(14,2)`) and the editor accepted a fractional market, so the
 * rule held only because nobody had broken it. These tests pin the three places it has to hold: the
 * stored type, the API that writes it, and the market the engine derives from it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IntegerMarketTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ScenarioRepository scenarios;
    @Autowired
    private ScoringEngine engine;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private String builder;
    private String draftKey;

    @BeforeEach
    void seed() throws Exception {
        for (String table : List.of("map_share", "round_statement", "round_result", "decision", "round",
                "player", "company_product", "company_staff", "company_rnd", "team", "run", "simulation")) {
            jdbc.update("delete from " + table);
        }
        jdbc.update("update scenario set frozen_for_package = null");
        jdbc.update("delete from simulation_package");
        jdbc.update("delete from scenario where key like 'pkg-%'");
        jdbc.update("delete from scenario where key like 'draft-%'");
        for (String table : List.of("company_profile", "app_user", "tenant")) {
            jdbc.update("delete from " + table);
        }

        long tenant = jdbc.queryForObject(
                "insert into tenant (code, name) values ('acme','acme') returning id", Long.class);
        jdbc.update("""
                insert into app_user (tenant_id, email, display_name, role, password_hash)
                values (?, 'builder@acme.test', 'Builder', 'BUILDER', ?)
                """, tenant, encoder.encode("password1234"));
        builder = login();

        // A tenant draft to edit: the shipped template is read-only, which is the point of a copy.
        MvcResult copied = mvc.perform(post("/api/templates/standard-v1/copy")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mine\"}"))
                .andExpect(status().isCreated()).andReturn();
        draftKey = json.readTree(copied.getResponse().getContentAsString()).get("key").asText();
    }

    /** The stored type is the rule: a market cannot even be written as a fraction. */
    @Test
    void the_market_columns_are_integers() {
        assertThat(columnType("product", "base_demand")).isEqualTo("integer");
        assertThat(columnType("customer_segment", "base_size")).isEqualTo("integer");
    }

    /** A product whose market is not a whole count is refused, and the message says what to do. */
    @Test
    void a_product_market_must_be_a_whole_number_of_items() throws Exception {
        Map<String, Object> product = product("P1", 4000.5);

        mvc.perform(put("/api/templates/" + draftKey + "/products")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("products", List.of(product)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("template.catalog_invalid"))
                .andExpect(jsonPath("$.params.field").value("baseDemand"));

        // The same product with a whole market is accepted. The payload is the **whole** catalogue: a
        // products PUT replaces the list, so sending one product would delete the others.
        Map<String, Object> whole = product("P1", 4000);
        List<Map<String, Object>> catalogue = new ArrayList<>();
        catalogue.add(whole);
        catalogue.add(product("P2", 1500));
        catalogue.add(product("P3", 600));
        mvc.perform(put("/api/templates/" + draftKey + "/products")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("products", catalogue))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "select base_demand from product where scenario_key = ? and code = 'P1'", Integer.class, draftKey))
                .isEqualTo(4000);
    }

    /** The same rule for a customer class: a class is a number of buyers. */
    @Test
    void a_customer_class_market_must_be_a_whole_number_of_items() throws Exception {
        Map<String, Object> segment = segment("SEG-BASIC", 4000.5);

        mvc.perform(put("/api/templates/" + draftKey + "/segments")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("segments", List.of(segment)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.params.field").value("baseSize"));
    }

    /**
     * The market the engine derives is whole, and its pieces add up: the class is never handed a
     * fraction of an item, and never handed more or fewer pieces than the market holds (D105).
     */
    @Test
    void the_derived_market_is_whole_and_adds_up() {
        Scenario scenario = scenarios.load("standard-v1");
        List<String> codes = scenario.products().stream().map(Scenario.ProductSpec::code).toList();
        List<ScoringEngine.TeamInput> teams = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            teams.add(team(scenario, i, 40 + i));
        }

        EnvironmentService.RoundEnvironment environment =
                EnvironmentService.RoundEnvironment.neutral(1, codes);
        ScoringEngine.RoundResult result = engine.compute(scenario, environment, teams);

        for (String code : codes) {
            java.math.BigDecimal served = engine.servedMarket(scenario, environment, code, teams.size());
            assertThat(served.stripTrailingZeros().scale())
                    .as("%s: the market is %s items", code, served)
                    .isLessThanOrEqualTo(0);

            java.math.BigDecimal handed = java.math.BigDecimal.ZERO;
            for (ScoringEngine.TeamOutcome outcome : result.teams()) {
                for (ScoringEngine.ProductLine line : outcome.lines()) {
                    if (line.product().equals(code)) {
                        assertThat(line.demand().stripTrailingZeros().scale())
                                .as("%s demand %s is whole", code, line.demand()).isLessThanOrEqualTo(0);
                        handed = handed.add(line.demand());
                    }
                }
            }
            assertThat(handed).as("%s: handed out %s of a %s-item market", code, handed, served)
                    .isEqualByComparingTo(served);
        }
    }

    // ------------------------------------------------------------------ helpers

    private String columnType(String table, String column) {
        return jdbc.queryForObject("""
                select data_type from information_schema.columns
                where table_name = ? and column_name = ?
                """, String.class, table, column);
    }

    private Map<String, Object> product(String code, double market) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", code);
        row.put("name", "Basic");
        row.put("basePrice", 1_000_000);
        row.put("baseUnitCost", 590_000);
        row.put("baseQuality", 50);
        row.put("baseDemand", market);
        row.put("unitsPerWorker", 40);
        row.put("incomeElasticity", 1);
        row.put("trendAffinity", 0);
        return row;
    }

    private Map<String, Object> segment(String code, double market) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", code);
        row.put("name", "Mass market");
        row.put("baseSize", market);
        row.put("basePurchasingPower", 1);
        row.put("incomeElasticity", 0.4);
        row.put("trendAffinity", 0);
        return row;
    }

    private ScoringEngine.TeamInput team(Scenario scenario, long id, int productionStaff) {
        Map<String, ScoringEngine.ProductState> products = new LinkedHashMap<>();
        for (Scenario.ProductSpec spec : scenario.products()) {
            products.put(spec.code(), new ScoringEngine.ProductState(spec.code(),
                    spec.basePrice(), spec.baseQuality(), spec.baseUnitCost(),
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));
        }
        Map<String, Integer> staff = new LinkedHashMap<>();
        staff.put("PRODUCTION", productionStaff);
        staff.put("SALES", 3);
        staff.put("RND", 2);
        staff.put("ADMIN", 2);
        List<DecisionPayload.ProductRow> production = new ArrayList<>();
        for (Scenario.ProductSpec spec : scenario.products()) {
            production.add(new DecisionPayload.ProductRow(spec.code(), java.math.BigDecimal.valueOf(120)));
        }
        DecisionPayload decision = new DecisionPayload(production, List.of(), List.of(), null,
                List.of(), List.of(), java.math.BigDecimal.ZERO, false);
        return new ScoringEngine.TeamInput(id, "ACTIVE", new java.math.BigDecimal("2000000000"),
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, staff, products, Map.of(), decision);
    }

    private String login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", "acme", "email", "builder@acme.test",
                                "password", "password1234"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }
}
