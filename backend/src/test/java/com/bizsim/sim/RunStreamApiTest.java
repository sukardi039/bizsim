package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The live channel (D20). The interesting properties are not "does SSE work" but "can a ticket be
 * replayed or stolen": the stream is unauthenticated by header, so the ticket is the whole defence.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunStreamApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    private final PasswordEncoder encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();

    @Test
    void a_ticket_cannot_be_obtained_without_a_token() throws Exception {
        mvc.perform(post("/api/runs/1/stream-ticket")).andExpect(status().isUnauthorized());
    }

    @Test
    void the_stream_rejects_a_ticket_that_was_never_issued() throws Exception {
        mvc.perform(get("/api/runs/1/events").param("ticket", "not-a-ticket"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void the_stream_rejects_a_missing_ticket() throws Exception {
        mvc.perform(get("/api/runs/1/events")).andExpect(status().isBadRequest());
    }

    @Test
    void a_ticket_is_single_use_and_bound_to_its_run() throws Exception {
        Fixture fixture = fixture();
        String ticket = ticket(fixture.playerToken, fixture.runId);

        MvcResult stream = mvc.perform(get("/api/runs/" + fixture.runId + "/events")
                        .param("ticket", ticket))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(stream.getResponse().getContentType())
                .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);

        // Same ticket again: already consumed.
        mvc.perform(get("/api/runs/" + fixture.runId + "/events").param("ticket", ticket))
                .andExpect(status().isUnauthorized());

        // A ticket for one run cannot be used on another.
        String other = ticket(fixture.playerToken, fixture.runId);
        mvc.perform(get("/api/runs/" + (fixture.runId + 1000) + "/events").param("ticket", other))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private record Fixture(long runId, String playerToken) {
    }

    private Fixture fixture() throws Exception {
        // Its own tenant and users: the demo seeder only runs under the dev profile (D16 tests).
        jdbc.update("delete from tenant where code = 'stream'");
        long tenant = jdbc.queryForObject(
                "insert into tenant (code, name) values ('stream', 'Stream') returning id", Long.class);
        user(tenant, "trainer@stream.test", "FACILITATOR");
        user(tenant, "player@stream.test", "PLAYER");
        user(tenant, "admin@stream.test", "ADMIN");

        String admin = login("admin@stream.test");
        Long facilitatorId = jdbc.queryForObject(
                "select id from app_user where email = 'trainer@stream.test'", Long.class);
        MvcResult created = mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(facilitatorId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Stream", "scenarioKey", "standard-v1",
                                "teams", List.of(Map.of("name", "A", "companyName", "A Inc"))))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = json.readTree(created.getResponse().getContentAsString());
        return new Fixture(body.get("id").asLong(), login("player@stream.test"));
    }

    private String ticket(String token, long runId) throws Exception {
        MvcResult result = mvc.perform(post("/api/runs/" + runId + "/stream-ticket")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("ticket").asText();
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
                                "tenantCode", "stream", "email", email,
                                "password", "pw-" + email))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
