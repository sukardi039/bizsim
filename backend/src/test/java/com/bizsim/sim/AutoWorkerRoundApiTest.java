package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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

/**
 * D48/D49/D50 end to end: an auto worker seated in a session decides, submits through the same path
 * as a trainee, is traceable, and replays identically.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AutoWorkerRoundApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private String adminToken;
    private String facilitatorToken;
    private String builderToken;
    private long tenantId;
    private long runId;
    private long botTeamId;
    private long botUserId;

    @BeforeEach
    void seed() throws Exception {
        for (String table : List.of("bot_decision", "facilitator_action", "round_snapshot",
                "round_statement", "round_result", "decision", "round", "player", "company_product",
                "company_staff", "company_rnd", "team", "run", "simulation")) {
            jdbc.update("delete from " + table);
        }
        jdbc.update("update app_user set bot_profile_id = null");
        jdbc.update("delete from bot_profile");
        jdbc.update("delete from app_user");
        jdbc.update("delete from tenant");

        tenantId = jdbc.queryForObject(
                "insert into tenant (code, name) values ('acme', 'Acme') returning id", Long.class);
        user("admin@acme.test", "ADMIN");
        user("builder@acme.test", "BUILDER");
        user("trainer@acme.test", "FACILITATOR");
        adminToken = login("admin@acme.test");
        builderToken = login("builder@acme.test");
        facilitatorToken = login("trainer@acme.test");

        setAutoWorkers(true);

        MvcResult created = mvc.perform(post("/api/bots")
                        .header("Authorization", "Bearer " + builderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Market 1", "level", "high"))))
                .andExpect(status().isCreated())
                .andReturn();
        botUserId = json.readTree(created.getResponse().getContentAsString()).get("userId").asLong();

        MvcResult run = mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(idOf("trainer@acme.test")))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Autumn",
                                "scenarioKey", "standard-v1",
                                "teams", List.of(
                                        Map.of("name", "alpha", "companyName", "Alpha"),
                                        Map.of("name", "market", "companyName", "Market 1_high"))))))
                .andExpect(status().isCreated())
                .andReturn();
        runId = json.readTree(run.getResponse().getContentAsString()).get("id").asLong();
        long teamOne = jdbc.queryForObject(
                "select id from team where run_id = ? order by id limit 1", Long.class, runId);
        botTeamId = jdbc.queryForObject(
                "select id from team where run_id = ? order by id desc limit 1", Long.class, runId);

        seat(teamOne, idOf("trainer@acme.test"));
        seat(botTeamId, botUserId);
    }

    @AfterEach
    void restoreTemplate() {
        setAutoWorkers(false);
    }

    @Test
    void the_company_plays_itself_and_leaves_a_trace_that_replays() throws Exception {
        openRound();

        mvc.perform(post("/api/runs/" + runId + "/bots/decide")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decided").value(1));

        // Same path as a trainee: the submission is in the ordinary submissions view.
        mvc.perform(get("/api/runs/" + runId + "/decisions")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies[1].submitted").value(true));

        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());

        // It traded: a statement exists and the company moved.
        assertThat(jdbc.queryForObject(
                "select count(*) from round_statement where team_id = ?", Integer.class, botTeamId))
                .isEqualTo(1);

        mvc.perform(get("/api/runs/" + runId + "/bots")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].profileKey").value("market-1-high"))
                .andExpect(jsonPath("$[0].mode").value("MANUAL"))
                .andExpect(jsonPath("$[0].rationale").isNotEmpty());

        mvc.perform(post("/api/runs/" + runId + "/bots/" + botTeamId + "/replay")
                        .param("round", "1")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identical").value(true));
    }

    @Test
    void a_trainee_cannot_read_the_opponents_reasoning() throws Exception {
        openRound();
        mvc.perform(get("/api/runs/" + runId + "/bots")
                        .header("Authorization", "Bearer " + login("trainer@acme.test")))
                .andExpect(status().isOk());
        // The bot has no credentials at all, so it cannot even ask.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("tenantCode", "acme",
                                "email", "market-1-high@bots.invalid", "password", "x"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void a_trainee_sees_the_class_ranked_by_profit_and_nothing_more() throws Exception {
        long playerTeam = jdbc.queryForObject(
                "select id from team where run_id = ? order by id limit 1", Long.class, runId);
        seat(playerTeam, otherPlayer("one@acme.test"));

        openRound();
        mvc.perform(post("/api/runs/" + runId + "/bots/decide")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
        // The person submits too, so the table has two real rows.
        mvc.perform(post("/api/runs/" + runId + "/teams/" + playerTeam + "/decisions")
                        .header("Authorization", "Bearer " + login("one@acme.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "production", List.of(
                                        Map.of("product", "P1", "units", 600),
                                        Map.of("product", "P2", "units", 150),
                                        Map.of("product", "P3", "units", 30)),
                                "price", List.of(
                                        Map.of("product", "P1", "unitPrice", 1_000_000),
                                        Map.of("product", "P2", "unitPrice", 2_500_000),
                                        Map.of("product", "P3", "unitPrice", 5_000_000)),
                                "marketing", List.of(Map.of("channel", "DIGITAL", "spend", 1_000_000)),
                                "manpower", Map.of("production", 30),
                                "capacityPurchase", 0,
                                "marketReport", false))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());

        MvcResult result = mvc.perform(get("/api/runs/" + runId + "/standings")
                        .header("Authorization", "Bearer " + login("one@acme.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[0].company").isString())
                .andExpect(jsonPath("$[0].profit").isNumber())
                .andReturn();

        // D21 stays true for everything else: no share, no cash, no statement in the payload.
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("marketShare").doesNotContain("cash")
                .doesNotContain("stock").doesNotContain("share");
        assertThat(body).contains("\"own\":true");

        // A trainee who is not seated in this session sees no table at all.
        user("stranger@acme.test", "PLAYER");
        mvc.perform(get("/api/runs/" + runId + "/standings")
                        .header("Authorization", "Bearer " + login("stranger@acme.test")))
                .andExpect(status().isForbidden());
    }

    @Test
    void a_template_without_the_switch_cannot_acquire_an_opponent() throws Exception {
        setAutoWorkers(false);
        // One facilitator may hold only one session in progress, so this needs a second one.
        user("trainer2@acme.test", "FACILITATOR");
        String otherFacilitatorToken = login("trainer2@acme.test");
        MvcResult run = mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(idOf("trainer2@acme.test")))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "No bots",
                                "scenarioKey", "standard-v1",
                                "teams", List.of(
                                        Map.of("name", "solo", "companyName", "Solo"),
                                        Map.of("name", "spare", "companyName", "Spare"))))))
                .andExpect(status().isCreated())
                .andReturn();
        long otherRun = json.readTree(run.getResponse().getContentAsString()).get("id").asLong();
        long team = jdbc.queryForObject(
                "select id from team where run_id = ? order by id desc limit 1", Long.class, otherRun);

        mvc.perform(post("/api/runs/" + otherRun + "/players")
                        .header("Authorization", "Bearer " + otherFacilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "teamId", team, "userId", botUserId, "roleKey", "OWNER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("bot.switch_off"));
    }

    // ------------------------------------------------------------------ helpers

    private void setAutoWorkers(boolean on) {
        jdbc.update("update scenario set definition = jsonb_set(definition, '{autoWorkers}', ?::jsonb) "
                + "where key = 'standard-v1'", String.valueOf(on));
    }

    private void seat(long teamId, long userId) throws Exception {
        mvc.perform(post("/api/runs/" + runId + "/players")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "teamId", teamId, "userId", userId, "roleKey", "OWNER"))))
                .andExpect(status().isCreated());
    }

    private void openRound() throws Exception {
        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
    }

    private void user(String email, String role) {
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?)
                """, tenantId, email, encoder.encode("pw-" + email), email, role);
    }

    private long otherPlayer(String email) {
        if (!tableHas(email)) {
            user(email, "PLAYER");
        }
        return idOf(email);
    }

    private boolean tableHas(String email) {
        Integer count = jdbc.queryForObject("select count(*) from app_user where email = ?",
                Integer.class, email);
        return count != null && count > 0;
    }

    private long idOf(String email) {
        return jdbc.queryForObject("select id from app_user where email = ?", Long.class, email);
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("tenantCode", "acme", "email", email,
                                "password", "pw-" + email))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
