package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D124: a bought market review is paid for the round, and reading it makes the purchase stick.
 *
 * <p>The hole this closes: the purchase travelled inside the submitted plan, and the plan is replaced on
 * every re-submission. So a trainee could buy the review, read the forecast, edit a price and submit again
 * with the box cleared — keeping the report and withdrawing its cost. Reading the report is what the money
 * pays for, so the purchase is recorded separately and re-submission cannot undo it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportPurchaseTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private long tenant;
    private long runId;
    private long teamId;
    private String trainee;
    private String trainer;

    @BeforeEach
    void seed() throws Exception {
        for (String table : List.of("report_purchase", "round_statement", "round_result", "decision",
                "round", "player", "company_product", "company_staff", "company_rnd", "team", "run",
                "simulation")) {
            jdbc.update("delete from " + table);
        }
        jdbc.update("update scenario set frozen_for_package = null");
        jdbc.update("delete from simulation_package");
        jdbc.update("delete from scenario where key like 'pkg-%'");
        for (String table : List.of("company_profile", "app_user", "tenant")) {
            jdbc.update("delete from " + table);
        }
        jdbc.update("""
                insert into company_profile (tenant_id, name, company_name, products, notes)
                values (null, 'Standard manufacturer', '', '[]'::jsonb, 'shipped')
                """);

        tenant = jdbc.queryForObject(
                "insert into tenant (code, name) values ('acme', 'Acme') returning id", Long.class);
        user("trainer@acme.test", "FACILITATOR");
        user("ada@acme.test", "PLAYER");
        trainer = login("trainer@acme.test");
        trainee = login("ada@acme.test");

        MvcResult created = mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(
                                jdbc.queryForObject("select id from app_user where email = 'trainer@acme.test'",
                                        Long.class)))
                        .header("Authorization", "Bearer " + builderToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Report class", "scenarioKey", "standard-v1",
                                "teams", List.of(Map.of("name", "A", "companyName", "A Ltd"))))))
                .andExpect(status().isCreated())
                .andReturn();
        runId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                        .header("Authorization", "Bearer " + trainer))
                .andExpect(status().isOk());
        teamId = jdbc.queryForObject("select id from team where run_id = ?", Long.class, runId);
        jdbc.update("""
                insert into player (team_id, user_id, role_key, run_id) values (?, ?, 'OWNER', ?)
                """, teamId, jdbc.queryForObject(
                "select id from app_user where email = 'ada@acme.test'", Long.class), runId);
    }

    @Test
    void reading_the_review_locks_the_purchase_for_the_round() throws Exception {
        // 1. The plan is submitted with no review: nothing bought.
        submit(false);
        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamId + "/context")
                        .header("Authorization", "Bearer " + trainee))
                .andExpect(jsonPath("$.submitted").value(true))
                .andExpect(jsonPath("$.reportPurchased").value(false));

        // 2. A second submission buys it.
        submit(true);
        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamId + "/context")
                        .header("Authorization", "Bearer " + trainee))
                .andExpect(jsonPath("$.reportPurchased").value(true));
        assertThat(reportCostCharged()).as("the review is billed after buying it").isPositive();

        // 3. The trainee edits the plan and submits with the review cleared — the hole this closes.
        submit(false);

        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamId + "/context")
                        .header("Authorization", "Bearer " + trainee))
                .andExpect(jsonPath("$.reportPurchased").value(true))
                .andExpect(jsonPath("$.decision.marketReport").value(true));
        assertThat(reportCostCharged()).as("re-submitting cannot withdraw the charge").isPositive();

        // 4. The trainee can still read it — the round is paid for.
        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamId + "/market-report")
                        .header("Authorization", "Bearer " + trainee))
                .andExpect(status().isOk());
    }

    /** The report line on the open round's stored plan — what the engine will charge at close. */
    private java.math.BigDecimal reportCostCharged() {
        return jdbc.queryForObject("""
                select coalesce((payload->>'marketReport')::boolean, false)
                from decision d join round r on r.id = d.round_id
                where r.run_id = ? and d.team_id = ?
                """, Boolean.class, runId, teamId) ? java.math.BigDecimal.ONE : java.math.BigDecimal.ZERO;
    }

    private void submit(boolean withReview) throws Exception {
        // The shipped template carries three products and a decision must cover all of them.
        List<Map<String, Object>> production = new ArrayList<>();
        List<Map<String, Object>> price = new ArrayList<>();
        for (String code : List.of("P1", "P2", "P3")) {
            production.add(Map.of("product", code, "units", 100));
            price.add(Map.of("product", code, "unitPrice", 1000000));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("production", production);
        body.put("price", price);
        body.put("marketing", List.of());
        body.put("manpower", Map.of("production", 60));
        body.put("rnd", List.of());
        body.put("quality", List.of());
        body.put("marketReport", withReview);
        mvc.perform(post("/api/runs/" + runId + "/teams/" + teamId + "/decisions")
                        .header("Authorization", "Bearer " + trainee)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private String builderToken() throws Exception {
        user("builder@acme.test", "BUILDER");
        return login("builder@acme.test");
    }

    private void user(String email, String role) {
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?)
                """, tenant, email, encoder.encode("pw-" + email), email, role);
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", "acme", "email", email, "password", "pw-" + email))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
