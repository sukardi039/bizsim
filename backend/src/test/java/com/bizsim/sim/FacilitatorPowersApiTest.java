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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D12/Q-G: the four facilitator powers the ruling promised and the console did not have — stop the
 * clock, read what every company sent, move a company's cash with a reason on the record, and undo
 * the last computed round.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FacilitatorPowersApiTest extends PostgresTestBase {

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
    private String playerToken;

    @BeforeEach
    void seed() throws Exception {
        for (String table : List.of("round_snapshot", "facilitator_action", "round_statement",
                "round_result", "decision", "round", "player", "company_product", "company_staff",
                "company_rnd", "team", "run", "simulation", "app_user", "tenant")) {
            jdbc.update("delete from " + table);
        }

        long tenant = jdbc.queryForObject(
                "insert into tenant (code, name) values ('acme', 'Acme') returning id", Long.class);
        user(tenant, "trainer@acme.test", "FACILITATOR");
        user(tenant, "one@acme.test", "PLAYER");
        user(tenant, "admin@acme.test", "ADMIN");
        facilitatorToken = login("trainer@acme.test");
        playerToken = login("one@acme.test");
        String adminToken = login("admin@acme.test");

        JsonNode run = createRun(adminToken);
        runId = run.get("id").asLong();
        teamOne = run.get("teams").get(0).get("id").asLong();
        teamTwo = run.get("teams").get(1).get("id").asLong();
        addPlayer(teamOne, "one@acme.test");
    }

    // ------------------------------------------------------------------ the clock

    @Test
    void the_clock_can_be_paused_and_resumed_and_the_deadline_moves_with_it() throws Exception {
        openRound();
        JsonNode started = run();
        assertThat(started.get("roundOpen").asBoolean()).isTrue();
        assertThat(started.get("clockPaused").asBoolean()).isFalse();
        long remaining = started.get("clockRemainingSeconds").asLong();
        assertThat(remaining).isBetween(1100L, 1201L); // 20 minutes, minus the test's own latency

        mvc.perform(post("/api/runs/" + runId + "/rounds/clock/pause")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true));

        // Frozen: the remaining time does not tick while the facilitator is talking.
        Thread.sleep(1100);
        long paused = run().get("clockRemainingSeconds").asLong();
        assertThat(paused).isEqualTo(remaining);
        assertThat(run().get("clockPaused").asBoolean()).isTrue();

        // Extending works while stopped, and applies to the frozen deadline.
        mvc.perform(post("/api/runs/" + runId + "/rounds/clock/extend")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("minutes", 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingSeconds").value(paused + 300));

        mvc.perform(post("/api/runs/" + runId + "/rounds/clock/resume")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false));

        JsonNode resumed = run();
        assertThat(resumed.get("clockPaused").asBoolean()).isFalse();
        assertThat(resumed.get("clockRemainingSeconds").asLong())
                .isBetween(paused + 298, paused + 301);

        // The clock is a facilitator power, and a trainee pressing the button gets a refusal.
        mvc.perform(post("/api/runs/" + runId + "/rounds/clock/pause")
                        .header("Authorization", "Bearer " + playerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.facilitator_only"));
    }

    @Test
    void pausing_never_computes_the_round() throws Exception {
        openRound();
        mvc.perform(post("/api/runs/" + runId + "/rounds/clock/pause")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());

        // The sweep only looks at rounds whose deadline passed and that are not paused.
        assertThat(jdbc.queryForList(
                "select id from round where run_id = ? and paused_at is null", Long.class, runId))
                .isEmpty();
        assertThat(jdbc.queryForObject("select status from round where run_id = ?", String.class, runId))
                .isEqualTo("OPEN");
    }

    // ------------------------------------------------------------------ submissions

    @Test
    void the_facilitator_sees_who_submitted_and_what() throws Exception {
        openRound();
        submit(teamOne, payload(600, 150, 30, 20_000_000L, 30))
                .andExpect(status().isOk());

        mvc.perform(get("/api/runs/" + runId + "/decisions")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.round").value(1))
                .andExpect(jsonPath("$.companies.length()").value(2))
                .andExpect(jsonPath("$.companies[0].submitted").value(true))
                .andExpect(jsonPath("$.companies[0].submittedBy").value("one@acme.test"))
                .andExpect(jsonPath("$.companies[0].decision.production[0].units").value(600))
                .andExpect(jsonPath("$.companies[1].submitted").value(false));

        // Competitive information: only the staff side may read the class's numbers.
        mvc.perform(get("/api/runs/" + runId + "/decisions")
                        .header("Authorization", "Bearer " + playerToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ cash

    @Test
    void a_cash_adjustment_needs_a_reason_and_is_audited() throws Exception {
        openRound();
        BigDecimal before = cashOf(teamOne);

        mvc.perform(post("/api/runs/" + runId + "/teams/" + teamOne + "/cash")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("delta", 1_000_000))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("cash.reason_required"));

        mvc.perform(post("/api/runs/" + runId + "/teams/" + teamOne + "/cash")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("delta", 0, "reason", "typo"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("cash.delta_required"));

        mvc.perform(post("/api/runs/" + runId + "/teams/" + teamOne + "/cash")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "delta", 1_000_000, "reason", "bank loan approved in class"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cash").value(before.add(new BigDecimal("1000000")).doubleValue()));

        assertThat(cashOf(teamOne)).isEqualByComparingTo(before.add(new BigDecimal("1000000")));

        mvc.perform(get("/api/runs/" + runId + "/audit")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("CASH_ADJUST"))
                .andExpect(jsonPath("$[0].amount").value(1000000))
                .andExpect(jsonPath("$[0].reason").value("bank loan approved in class"))
                .andExpect(jsonPath("$[0].companyName").value("Alpha Inc"))
                .andExpect(jsonPath("$[0].actor").value("trainer@acme.test"));
    }

    // ------------------------------------------------------------------ void and recompute

    @Test
    void voiding_the_last_round_restores_every_company_and_reopens_the_month() throws Exception {
        openRound();
        submit(teamOne, payload(600, 150, 30, 20_000_000L, 30)).andExpect(status().isOk());
        compute();

        BigDecimal afterRound = cashOf(teamOne);
        assertThat(afterRound).isNotEqualByComparingTo("2000000000");
        assertThat(count("round_statement")).isEqualTo(2);
        assertThat(count("round_result")).isEqualTo(2);
        assertThat(run().get("roundOpen").asBoolean()).isFalse();

        mvc.perform(post("/api/runs/" + runId + "/rounds/void")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reason", "price was typed wrong"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.round").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));

        // Every company is exactly where it stood before the month ran.
        assertThat(cashOf(teamOne)).isEqualByComparingTo("2000000000");
        assertThat(stockOf(teamOne, "P1")).isEqualByComparingTo("0");
        assertThat(count("round_statement")).isZero();
        assertThat(count("round_result")).isZero();
        assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, runId))
                .isEqualTo("OPEN");
        JsonNode reopened = run();
        assertThat(reopened.get("roundOpen").asBoolean()).isTrue();
        assertThat(reopened.get("clockRemainingSeconds").asLong()).isGreaterThan(0);
        assertThat(reopened.get("status").asText()).isEqualTo("OPEN");

        mvc.perform(get("/api/runs/" + runId + "/audit")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(jsonPath("$[0].action").value("ROUND_VOID"))
                .andExpect(jsonPath("$[0].reason").value("price was typed wrong"));

        // The decision survived (the class corrects it, it does not retype it) and recomputing the
        // same month is deterministic: same cash as the first computation.
        compute();
        assertThat(cashOf(teamOne)).isEqualByComparingTo(afterRound);
    }

    @Test
    void voiding_refuses_a_round_computed_before_snapshots_existed() throws Exception {
        openRound();
        submit(teamOne, payload(600, 150, 30, 20_000_000L, 30)).andExpect(status().isOk());
        compute();
        BigDecimal computed = cashOf(teamOne);

        // Rounds computed before V19 carry no snapshot; restoring them would have to guess.
        jdbc.update("delete from round_snapshot");
        mvc.perform(post("/api/runs/" + runId + "/rounds/void")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("round.no_snapshot"));

        assertThat(cashOf(teamOne)).isEqualByComparingTo(computed);
        assertThat(count("round_statement")).isEqualTo(2);
    }

    @Test
    void a_round_that_is_still_open_cannot_be_voided() throws Exception {
        openRound();
        mvc.perform(post("/api/runs/" + runId + "/rounds/void")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ helpers

    private void openRound() throws Exception {
        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
    }

    private void compute() throws Exception {
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
    }

    private JsonNode run() throws Exception {
        MvcResult result = mvc.perform(get("/api/runs/" + runId)
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private BigDecimal cashOf(long teamId) {
        return jdbc.queryForObject("select cash from team where id = ?", BigDecimal.class, teamId);
    }

    private BigDecimal stockOf(long teamId, String product) {
        return jdbc.queryForObject(
                "select stock from company_product where team_id = ? and product_code = ?",
                BigDecimal.class, teamId, product);
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private org.springframework.test.web.servlet.ResultActions submit(long teamId,
                                                                      Map<String, Object> body)
            throws Exception {
        return mvc.perform(post("/api/runs/" + runId + "/teams/" + teamId + "/decisions")
                .header("Authorization", "Bearer " + playerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private static Map<String, Object> payload(int p1, int p2, int p3, long marketing, int production) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("production", List.of(
                Map.of("product", "P1", "units", p1),
                Map.of("product", "P2", "units", p2),
                Map.of("product", "P3", "units", p3)));
        body.put("price", List.of(
                Map.of("product", "P1", "unitPrice", 1_000_000),
                Map.of("product", "P2", "unitPrice", 2_500_000),
                Map.of("product", "P3", "unitPrice", 5_000_000)));
        body.put("marketing", List.of(Map.of("channel", "DIGITAL", "spend", marketing)));
        body.put("manpower", Map.of("production", production));
        body.put("rnd", List.of(Map.of("project", "RND-P1-COST", "invest", 0)));
        body.put("capacityPurchase", 0);
        body.put("marketReport", false);
        return body;
    }

    private JsonNode createRun(String adminToken) throws Exception {
        MvcResult result = mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(idOf("trainer@acme.test")))
                        .header("Authorization", "Bearer " + adminToken)
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
        mvc.perform(post("/api/runs/" + runId + "/players")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "teamId", teamId, "userId", idOf(email), "roleKey", "OWNER"))))
                .andExpect(status().isCreated());
    }

    private void user(long tenantId, String email, String role) {
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?)
                """, tenantId, email, encoder.encode("pw-" + email), email, role);
    }

    private long idOf(String email) {
        return jdbc.queryForObject("select id from app_user where email = ?", Long.class, email);
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
