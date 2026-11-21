package com.bizsim;

import com.bizsim.support.PostgresTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D117: the facilitator pushes the market-share pies onto the class's own screens.
 *
 * <p>The properties that matter, and that a facilitator will ask about in a room:
 *
 * <ol>
 *   <li>a show is a state of the <em>run</em>, so a trainee who reloads still finds it up;</li>
 *   <li>the pies are refused while nothing is being shown — otherwise the "push" is presentation only and
 *       a curious trainee reads the chart before the class does;</li>
 *   <li>closing puts it away for everyone, and the facilitator can still read the pies afterwards;</li>
 *   <li>only a facilitator may open or close one.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClassShowApiTest extends PostgresTestBase {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    @Test
    void a_show_reaches_the_trainee_and_the_pies_are_refused_without_one() throws Exception {
        long tenantId = jdbc.queryForObject(
                "insert into tenant (code, name) values ('acme-show', 'Show') returning id", Long.class);
        user(tenantId, "admin@show.test", "ADMIN");
        user(tenantId, "trainer@show.test", "FACILITATOR");
        user(tenantId, "one@show.test", "PLAYER");
        user(tenantId, "two@show.test", "PLAYER");
        String admin = login("acme-show", "admin@show.test");
        String trainer = login("acme-show", "trainer@show.test");
        String one = login("acme-show", "one@show.test");
        String two = login("acme-show", "two@show.test");

        long facilitatorId = jdbc.queryForObject(
                "select id from app_user where email = 'trainer@show.test'", Long.class);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Cohort");
        body.put("scenarioKey", "standard-v1");
        body.put("teams", List.of(
                Map.of("name", "Alpha", "companyName", "Alpha Inc"),
                Map.of("name", "Beta", "companyName", "Beta Inc")));
        MvcResult created = mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(facilitatorId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode run = json.readTree(created.getResponse().getContentAsString());
        long runId = run.get("id").asLong();
        long alpha = run.get("teams").get(0).get("id").asLong();
        long beta = run.get("teams").get(1).get("id").asLong();
        seat(trainer, runId, alpha, "one@show.test");
        seat(trainer, runId, beta, "two@show.test");

        // A computed round, because the pies are per round and a round with no statements has none.
        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                .header("Authorization", "Bearer " + trainer)).andExpect(status().isOk());
        submit(one, runId, alpha);
        submit(two, runId, beta);
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + trainer)).andExpect(status().isOk());

        // Nothing is being shown: no show, and no pies for a trainee.
        assertThat(showOf(one, runId).get("open").asBoolean()).isFalse();
        mvc.perform(get("/api/runs/" + runId + "/market-pies?round=1")
                        .header("Authorization", "Bearer " + one))
                .andExpect(status().isForbidden());

        // Only a facilitator may push one.
        mvc.perform(post("/api/runs/" + runId + "/show")
                        .header("Authorization", "Bearer " + one)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("round", 1))))
                .andExpect(status().isForbidden());

        // The push itself.
        MvcResult opened = mvc.perform(post("/api/runs/" + runId + "/show")
                        .header("Authorization", "Bearer " + trainer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("round", 1))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode state = json.readTree(
                opened.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(state.get("open").asBoolean()).isTrue();
        assertThat(state.get("round").asInt()).isEqualTo(1);
        assertThat(state.get("kind").asText()).isEqualTo("MARKET_PIES");

        // Both seated trainees see it — it is state of the run, not of one socket. A reload is the same call.
        for (String token : List.of(one, two)) {
            JsonNode seen = showOf(token, runId);
            assertThat(seen.get("open").asBoolean()).as("the show survives a reload").isTrue();
            assertThat(seen.get("round").asInt()).isEqualTo(1);
        }
        MvcResult pies = mvc.perform(get("/api/runs/" + runId + "/market-pies?round=1")
                        .header("Authorization", "Bearer " + one))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode pieRows = json.readTree(
                pies.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(pieRows.size()).as("one pie per product").isEqualTo(3);

        // Closing takes it down for everyone and closes the door again — while the facilitator keeps
        // their own view, because the projector is not the push.
        mvc.perform(delete("/api/runs/" + runId + "/show")
                        .header("Authorization", "Bearer " + trainer))
                .andExpect(status().isOk());
        assertThat(showOf(one, runId).get("open").asBoolean()).isFalse();
        mvc.perform(get("/api/runs/" + runId + "/market-pies?round=1")
                        .header("Authorization", "Bearer " + one))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/runs/" + runId + "/market-pies?round=1")
                        .header("Authorization", "Bearer " + trainer))
                .andExpect(status().isOk());

        // And the session control is in the audit trail, as a class will ask when it happened.
        List<String> actions = jdbc.queryForList("""
                select action from facilitator_action where run_id = ? order by id
                """, String.class, runId);
        assertThat(actions).contains("SHOW_OPEN", "SHOW_CLOSE");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode showOf(String token, long runId) throws Exception {
        MvcResult result = mvc.perform(get("/api/runs/" + runId + "/show")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void seat(String trainer, long runId, long teamId, String email) throws Exception {
        Long userId = jdbc.queryForObject("select id from app_user where email = ?", Long.class, email);
        mvc.perform(post("/api/runs/" + runId + "/players")
                        .header("Authorization", "Bearer " + trainer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "teamId", teamId, "userId", userId, "roleKey", "OWNER"))))
                .andExpect(status().isCreated());
    }

    private void submit(String token, long runId, long teamId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("marketReport", false);
        body.put("production", List.of(
                Map.of("product", "P1", "units", 400),
                Map.of("product", "P2", "units", 100),
                Map.of("product", "P3", "units", 20)));
        body.put("price", List.of(
                Map.of("product", "P1", "unitPrice", 1_000_000),
                Map.of("product", "P2", "unitPrice", 2_500_000),
                Map.of("product", "P3", "unitPrice", 5_000_000)));
        body.put("marketing", List.of(Map.of("channel", "DIGITAL", "spend", 20_000_000)));
        body.put("manpower", Map.of("production", 40));
        body.put("rnd", List.of());
        body.put("capacityPurchase", 0);
        mvc.perform(post("/api/runs/" + runId + "/teams/" + teamId + "/decisions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private void user(long tenantId, String email, String role) {
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?)
                """, tenantId, email, encoder.encode("pw-" + email), email, role);
    }

    private String login(String tenantCode, String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", tenantCode, "email", email,
                                "password", "pw-" + email))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
