package com.bizsim.run;

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

@SpringBootTest
@AutoConfigureMockMvc
class RunApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private long acmeTenant;
    private long globexTenant;
    private long acmeFacilitator;
    private String facilitatorToken;
    private String playerToken;

    @BeforeEach
    void seed() throws Exception {
        jdbc.update("delete from round_result");
        jdbc.update("delete from decision");
        jdbc.update("delete from round");
        jdbc.update("delete from player");
        jdbc.update("delete from team");
        jdbc.update("delete from run");
        jdbc.update("delete from simulation");
        jdbc.update("delete from app_user");
        jdbc.update("delete from tenant");

        acmeTenant = tenant("acme", "Acme");
        globexTenant = tenant("globex", "Globex");

        acmeFacilitator = user(acmeTenant, "trainer@acme.test", "FACILITATOR");
        user(acmeTenant, "player@acme.test", "PLAYER");
        user(globexTenant, "trainer@globex.test", "FACILITATOR");

        facilitatorToken = login("acme", "trainer@acme.test");
        playerToken = login("acme", "player@acme.test");
    }

    @Test
    void facilitator_creates_a_run_with_teams() throws Exception {
        mvc.perform(post("/api/runs").header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Cohort November",
                                "scenarioKey", "standard-v1",
                                "teams", List.of(
                                        Map.of("name", "Alpha", "companyName", "Alpha Industries"),
                                        Map.of("name", "Beta", "companyName", "Beta Corp"))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.currentRound").value(0))
                .andExpect(jsonPath("$.teams.length()").value(2))
                .andExpect(jsonPath("$.teams[0].companyName").value("Alpha Industries"))
                .andExpect(jsonPath("$.teams[0].cash").value(100000.00));
    }

    @Test
    void players_cannot_create_runs() throws Exception {
        mvc.perform(post("/api/runs").header("Authorization", "Bearer " + playerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Nope", "scenarioKey", "standard-v1",
                                "teams", List.of(Map.of("name", "A", "companyName", "A"))))))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknown_scenario_is_400() throws Exception {
        mvc.perform(post("/api/runs").header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "X", "scenarioKey", "does-not-exist",
                                "teams", List.of(Map.of("name", "A", "companyName", "A"))))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void another_tenants_run_is_404_not_403() throws Exception {
        long runId = createRun(facilitatorToken);

        String globexToken = login("globex", "trainer@globex.test");
        mvc.perform(get("/api/runs/" + runId).header("Authorization", "Bearer " + globexToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void advancing_closes_the_previous_round_and_opens_the_next() throws Exception {
        long runId = createRun(facilitatorToken);

        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));

        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(2));

        Map<String, Object> round1 = jdbc.queryForMap(
                "select status, closed_at from round where run_id = ? and number = 1", runId);
        assertThat(round1.get("status")).isEqualTo("CLOSED");
        assertThat(round1.get("closed_at")).isNotNull();

        mvc.perform(get("/api/runs/" + runId).header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(jsonPath("$.currentRound").value(2));
    }

    @Test
    void advancing_past_the_scenario_round_count_is_409() throws Exception {
        long runId = createRun(facilitatorToken);
        for (int i = 1; i <= 6; i++) {
            mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                            .header("Authorization", "Bearer " + facilitatorToken))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isConflict());

        // The failed advance must not have closed round 6.
        String status6 = jdbc.queryForObject(
                "select status from round where run_id = ? and number = 6", String.class, runId);
        assertThat(status6).isEqualTo("OPEN");
    }

    @Test
    void a_player_from_another_tenant_cannot_be_added() throws Exception {
        long runId = createRun(facilitatorToken);
        long teamId = jdbc.queryForObject(
                "select id from team where run_id = ? order by id limit 1", Long.class, runId);
        Long globexUserId = jdbc.queryForObject(
                "select id from app_user where tenant_id = ? limit 1", Long.class, globexTenant);

        mvc.perform(post("/api/runs/" + runId + "/players")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "teamId", teamId, "userId", globexUserId, "roleKey", "CEO"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicate_team_names_conflict() throws Exception {
        long runId = createRun(facilitatorToken);
        mvc.perform(post("/api/runs/" + runId + "/teams")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Alpha", "companyName", "Alpha Again"))))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ helpers

    private long createRun(String token) throws Exception {
        MvcResult result = mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Cohort",
                                "scenarioKey", "standard-v1",
                                "teams", List.of(Map.of("name", "Alpha", "companyName", "Alpha Inc"))))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return body.get("id").asLong();
    }

    private long tenant(String code, String name) {
        return jdbc.queryForObject(
                "insert into tenant (code, name) values (?, ?) returning id", Long.class, code, name);
    }

    private long user(long tenantId, String email, String role) {
        return jdbc.queryForObject("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?) returning id
                """, Long.class, tenantId, email, encoder.encode("pw-" + email), email, role);
    }

    private String login(String tenant, String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", tenant, "email", email, "password", "pw-" + email))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
