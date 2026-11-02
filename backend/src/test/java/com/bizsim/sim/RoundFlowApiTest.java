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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** S2a exit test: a real round goes in, a deterministic statement comes out. */
@SpringBootTest
@AutoConfigureMockMvc
class RoundFlowApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private long runId;
    private long teamOne;
    private long teamTwo;
    private String facilitatorToken;
    private String playerOneToken;

    @BeforeEach
    void seed() throws Exception {
        for (String table : List.of("round_statement", "round_result", "decision", "round", "player",
                "company_product", "company_staff", "company_rnd", "team", "run", "simulation",
                "app_user", "tenant")) {
            jdbc.update("delete from " + table);
        }

        long tenant = jdbc.queryForObject(
                "insert into tenant (code, name) values ('acme', 'Acme') returning id", Long.class);
        user(tenant, "trainer@acme.test", "FACILITATOR");
        user(tenant, "one@acme.test", "PLAYER");
        user(tenant, "two@acme.test", "PLAYER");
        facilitatorToken = login("trainer@acme.test");
        playerOneToken = login("one@acme.test");
        String playerTwoToken = login("two@acme.test");

        JsonNode run = createRun();
        runId = run.get("id").asLong();
        teamOne = run.get("teams").get(0).get("id").asLong();
        teamTwo = run.get("teams").get(1).get("id").asLong();

        addPlayer(teamOne, "one@acme.test");
        addPlayer(teamTwo, "two@acme.test");
        assertThat(playerTwoToken).isNotBlank();
    }

    @Test
    void a_submitted_month_is_computed_for_every_company() throws Exception {
        openRound();

        submit(playerOneToken, teamOne, payload(600, 150, 30, 20_000_000L, 30, 0))
                .andExpect(status().isOk());

        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teams.length()").value(2));

        List<Map<String, Object>> board = jdbc.queryForList("""
                select rr.team_id, (rr.score->>'profit')::numeric as profit from round_result rr
                join round r on r.id = rr.round_id where r.run_id = ? order by rr.team_id
                """, runId);
        assertThat(board).hasSize(2);

        // The company that submitted sells 600/150/30 at full capacity; the silent one sells nothing
        // and still pays payroll and fixed cost.
        assertThat(profit(board, teamOne)).isGreaterThan(profit(board, teamTwo));
        assertThat(profit(board, teamTwo)).isLessThan(java.math.BigDecimal.ZERO);

        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamOne + "/statements")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].round").value(1))
                .andExpect(jsonPath("$[0].revenue").isNumber());
    }

    @Test
    void closing_twice_does_not_compute_the_month_twice() throws Exception {
        openRound();
        submit(playerOneToken, teamOne, payload(600, 150, 30, 20_000_000L, 30, 0))
                .andExpect(status().isOk());

        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isNoContent());

        Integer results = jdbc.queryForObject("""
                select count(*) from round_result rr join round r on r.id = rr.round_id
                where r.run_id = ? and r.number = 1
                """, Integer.class, runId);
        assertThat(results).isEqualTo(2);
    }

    @Test
    void a_plan_that_needs_more_workers_than_the_company_has_is_rejected() throws Exception {
        openRound();
        // 10,000 units of P1 at 40 units per worker needs 250 workers; the company starts with 30.
        submit(playerOneToken, teamOne, payload(10_000, 0, 0, 0L, 30, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("workers")));
    }

    @Test
    void spending_more_than_the_bank_balance_is_409_with_a_breakdown() throws Exception {
        openRound();
        submit(playerOneToken, teamOne, payload(600, 150, 30, 5_000_000_000L, 30, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("insufficient cash"))
                .andExpect(jsonPath("$.lines.marketing").value(5_000_000_000L));
    }

    @Test
    void a_player_cannot_submit_for_another_company() throws Exception {
        openRound();
        submit(playerOneToken, teamTwo, payload(600, 150, 30, 0L, 30, 0))
                .andExpect(status().isForbidden());
    }

    @Test
    void an_unknown_field_is_rejected_rather_than_ignored() throws Exception {
        openRound();
        Map<String, Object> body = new java.util.LinkedHashMap<>(
                payload(600, 150, 30, 0L, 30, 0));
        body.put("colour", "red");
        mvc.perform(post("/api/runs/" + runId + "/teams/" + teamOne + "/decisions")
                        .header("Authorization", "Bearer " + playerOneToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void a_submission_after_the_clock_deadline_is_409() throws Exception {
        openRound();
        jdbc.update("update round set closes_at = now() - interval '1 minute' where run_id = ?", runId);

        submit(playerOneToken, teamOne, payload(600, 150, 30, 0L, 30, 0))
                .andExpect(status().isConflict());
    }

    @Test
    void the_form_context_never_exposes_the_hidden_project_cost() throws Exception {
        openRound();
        MvcResult result = mvc.perform(get("/api/runs/" + runId + "/teams/" + teamOne + "/context")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rndProjects.length()").value(5))
                .andExpect(jsonPath("$.products.length()").value(3))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("hiddenCost");
        assertThat(body).contains("hintLow");
        assertThat(json.readTree(body).get("rndProjects").get(0).has("hintHigh")).isTrue();
    }

    @Test
    void three_months_in_sequence_accumulate_state_without_drift() throws Exception {
        // RND-P1-COST costs 300M over 3 months: 100M a month must complete it exactly at month 3.
        openRound();
        for (int month = 1; month <= 3; month++) {
            submit(playerOneToken, teamOne, payload(600, 150, 30, 20_000_000L, 30, 100_000_000L))
                    .andExpect(status().isOk());
            if (month < 3) {
                advance();
            }
        }
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());

        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamOne + "/statements")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        Map<String, Object> project = jdbc.queryForMap("""
                select status, benefit_factor, months_invested from company_rnd
                where team_id = ? and project_code = 'RND-P1-COST'
                """, teamOne);
        assertThat(project.get("status")).isEqualTo("COMPLETED");
        assertThat((java.math.BigDecimal) project.get("benefit_factor")).isEqualByComparingTo("1.0000");
        assertThat(project.get("months_invested")).isEqualTo(3);

        Map<String, Object> team = jdbc.queryForMap(
                "select cash, brand_score, cumulative_profit, cumulative_revenue from team where id = ?", teamOne);
        // A company that sells at capacity with a sane cost base is profitable: cash grows, it does
        // not drain. This is the calibration guard, not a business assertion.
        assertThat((java.math.BigDecimal) team.get("cash")).isGreaterThan(new java.math.BigDecimal("2000000000"));
        assertThat((java.math.BigDecimal) team.get("cumulative_profit")).isGreaterThan(java.math.BigDecimal.ZERO);
        assertThat((java.math.BigDecimal) team.get("cumulative_revenue")).isGreaterThan(new java.math.BigDecimal("2000000000"));
        // Three months of 20M in digital: brand rises, it does not spike.
        assertThat((java.math.BigDecimal) team.get("brand_score")).isGreaterThan(new java.math.BigDecimal("50"));
    }

    @Test
    void the_market_report_is_paywalled_and_then_readable() throws Exception {
        openRound();

        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamOne + "/market-report")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isPaymentRequired());

        submit(playerOneToken, teamOne, payload(600, 150, 30, 0L, 30, 0, true))
                .andExpect(status().isOk());

        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamOne + "/market-report")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cost").value(15000000))
                .andExpect(jsonPath("$.forecast.length()").value(2))
                .andExpect(jsonPath("$.current.demandIndex").isNumber());

        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());

        java.math.BigDecimal reportCost = jdbc.queryForObject(
                "select report_cost from round_statement where team_id = ?", java.math.BigDecimal.class, teamOne);
        assertThat(reportCost).isEqualByComparingTo("15000000");
    }

    @Test
    void cycles_are_configurable_and_rounds_can_exceed_the_default() throws Exception {
        MvcResult created = mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Three cycles", "scenarioKey", "standard-v1", "cycles", 3,
                                "teams", List.of(Map.of("name", "A", "companyName", "A Inc"))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rounds").value(12))
                .andExpect(jsonPath("$.cycles").value(3))
                .andReturn();
        long newRun = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        // Twelve rounds of economy were generated, and the third cycle carries the drift.
        Integer generated = jdbc.queryForObject(
                "select count(*) from run_round_economy where run_id = ?", Integer.class, newRun);
        assertThat(generated).isEqualTo(12);
        java.math.BigDecimal roundNine = jdbc.queryForObject(
                "select demand_index from run_round_economy where run_id = ? and round_number = 9",
                java.math.BigDecimal.class, newRun);
        assertThat(roundNine).isEqualByComparingTo("1.040");

        // Past the eight-round default: every round must still open, and the thirteenth must not.
        for (int i = 1; i <= 12; i++) {
            mvc.perform(post("/api/runs/" + newRun + "/rounds/next")
                    .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
        }
        mvc.perform(post("/api/runs/" + newRun + "/rounds/next")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ helpers

    private void advance() throws Exception {
        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions submit(String token, long teamId,
                                                                      Map<String, Object> body)
            throws Exception {
        return mvc.perform(post("/api/runs/" + runId + "/teams/" + teamId + "/decisions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private static Map<String, Object> payload(int p1, int p2, int p3, long marketing, int production,
                                               long rnd) {
        return payload(p1, p2, p3, marketing, production, rnd, false);
    }

    private static Map<String, Object> payload(int p1, int p2, int p3, long marketing, int production,
                                               long rnd, boolean marketReport) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("marketReport", marketReport);
        body.putAll(Map.of(
                "production", List.of(
                        Map.of("product", "P1", "units", p1),
                        Map.of("product", "P2", "units", p2),
                        Map.of("product", "P3", "units", p3)),
                "price", List.of(
                        Map.of("product", "P1", "unitPrice", 1_000_000),
                        Map.of("product", "P2", "unitPrice", 2_500_000),
                        Map.of("product", "P3", "unitPrice", 5_000_000)),
                "marketing", List.of(Map.of("channel", "DIGITAL", "spend", marketing)),
                "manpower", Map.of("production", production),
                "rnd", List.of(Map.of("project", "RND-P1-COST", "invest", rnd)),
                "capacityPurchase", 0));
        return body;
    }

    private void openRound() throws Exception {
        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
    }

    private JsonNode createRun() throws Exception {
        MvcResult result = mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Cohort",
                                "scenarioKey", "standard-v1",
                                "teams", List.of(
                                        Map.of("name", "Alpha", "companyName", "Alpha Inc"),
                                        Map.of("name", "Beta", "companyName", "Beta Inc"))))))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void addPlayer(long teamId, String email) throws Exception {
        Long userId = jdbc.queryForObject("select id from app_user where email = ?", Long.class, email);
        mvc.perform(post("/api/runs/" + runId + "/players")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "teamId", teamId, "userId", userId, "roleKey", "OWNER"))))
                .andExpect(status().isCreated());
    }

    private static java.math.BigDecimal profit(List<Map<String, Object>> board, long teamId) {
        return board.stream().filter(row -> ((Number) row.get("team_id")).longValue() == teamId)
                .map(row -> (java.math.BigDecimal) row.get("profit")).findFirst().orElseThrow();
    }

    private void user(long tenantId, String email, String role) {
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?)
                """, tenantId, email, encoder.encode("pw-" + email), email, role);
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
