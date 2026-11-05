package com.bizsim.user;

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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D47: the auto-worker library — who may create one, what the name means, and that it can never
 * sign in.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BotLibraryApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private String builderToken;
    private String facilitatorToken;
    private long tenantId;

    @BeforeEach
    void seed() throws Exception {
        // Children first: the seat and the person both point at a profile.
        for (String table : List.of("bot_decision", "player", "round", "team", "run", "simulation")) {
            jdbc.update("delete from " + table);
        }
        if (tableExists("app_user")) {
            jdbc.update("update app_user set bot_profile_id = null");
        }
        jdbc.update("delete from bot_profile");
        jdbc.update("delete from app_user");
        jdbc.update("delete from tenant");
        tenantId = jdbc.queryForObject(
                "insert into tenant (code, name) values ('acme', 'Acme') returning id", Long.class);
        user("builder@acme.test", "BUILDER");
        user("trainer@acme.test", "FACILITATOR");
        builderToken = login("builder@acme.test");
        facilitatorToken = login("trainer@acme.test");
    }

    @Test
    void a_builder_creates_a_named_brain_and_its_identity() throws Exception {
        MvcResult created = mvc.perform(post("/api/bots")
                        .header("Authorization", "Bearer " + builderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Market 1", "level", "high", "personality", "aggressive"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Market 1_high"))
                .andExpect(jsonPath("$.level").value("HIGH"))
                .andExpect(jsonPath("$.personality").value("AGGRESSIVE"))
                .andReturn();

        long profileId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        Map<String, Object> person = jdbc.queryForMap(
                "select role, display_name from app_user where bot_profile_id = ?", profileId);
        assertThat(person.get("role")).isEqualTo("AUTO_WORKER");
        assertThat(person.get("display_name")).isEqualTo("Market 1_high");

        // It is an identity, not an account: no credentials reach the login form at all.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("tenantCode", "acme",
                                "email", "market-1-high@bots.invalid", "password", "anything-at-all"))))
                .andExpect(status().isUnauthorized());

        // And it is not offered as a trainee when someone seats a class.
        mvc.perform(get("/api/users/trainees")
                        .header("Authorization", "Bearer " + builderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void the_suffix_wins_over_the_level_field_so_a_rename_cannot_swap_the_brain() throws Exception {
        mvc.perform(post("/api/bots")
                        .header("Authorization", "Bearer " + builderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Market 2_low", "level", "HIGH"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Market 2_low"))
                .andExpect(jsonPath("$.level").value("LOW"));
    }

    @Test
    void only_preparation_may_add_an_opponent() throws Exception {
        mvc.perform(post("/api/bots")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Market 3", "level", "mid"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.builder_only"));
    }

    @Test
    void editing_bumps_the_version_so_history_keeps_its_owner() throws Exception {
        MvcResult created = mvc.perform(post("/api/bots")
                        .header("Authorization", "Bearer " + builderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Market 4", "level", "mid"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(put("/api/bots/" + id)
                        .header("Authorization", "Bearer " + builderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Market 4", "level", "high",
                                "params", Map.of("targetShare", 0.31)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.name").value("Market 4_high"));

        // The person follows the profile's name, so a scoreboard never shows a stale label.
        assertThat(jdbc.queryForObject(
                "select display_name from app_user where bot_profile_id = ?", String.class, id))
                .isEqualTo("Market 4_high");
    }

    @Test
    void a_brain_that_has_played_is_retired_never_deleted() throws Exception {
        MvcResult created = mvc.perform(post("/api/bots")
                        .header("Authorization", "Bearer " + builderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Market 5", "level", "low"))))
                .andExpect(status().isCreated())
                .andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        long personId = jdbc.queryForObject(
                "select id from app_user where bot_profile_id = ?", Long.class, id);

        // Pretend it played: a seat points at the brain.
        long simulation = jdbc.queryForObject("""
                insert into simulation (tenant_id, name, scenario_key, facilitator_id)
                values (?, 'S', 'standard-v1', (select id from app_user where email = 'trainer@acme.test'))
                returning id
                """, Long.class, tenantId);
        long run = jdbc.queryForObject("""
                insert into run (tenant_id, simulation_id, scenario_key, status)
                values (?, ?, 'standard-v1', 'OPEN') returning id
                """, Long.class, tenantId, simulation);
        long team = jdbc.queryForObject("""
                insert into team (run_id, name, company_name) values (?, 'market-5', 'Market 5_high')
                returning id
                """, Long.class, run);
        jdbc.update("insert into player (run_id, team_id, user_id, role_key, bot_profile_id) "
                + "values (?, ?, ?, 'OWNER', ?)", run, team, personId, id);

        mvc.perform(post("/api/bots/" + id + "/retire")
                        .header("Authorization", "Bearer " + builderToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("bot.in_use"));
    }

    // ------------------------------------------------------------------ helpers

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = ?", Integer.class, table);
        return count != null && count > 0;
    }

    private void user(String email, String role) {
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?)
                """, tenantId, email, encoder.encode("pw-" + email), email, role);
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
