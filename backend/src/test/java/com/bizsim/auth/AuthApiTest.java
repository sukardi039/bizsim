package com.bizsim.auth;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTest extends PostgresTestBase {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void clean() {
        jdbc.update("delete from player");
        jdbc.update("delete from app_user");
        jdbc.update("delete from tenant");
    }

    @Test
    void login_returns_a_token_that_carries_the_tenant() throws Exception {
        long acme = seedTenant("acme", "Acme Corp");
        seedUser(acme, "trainer@acme.test", PASSWORD, "FACILITATOR");

        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login("acme", "trainer@acme.test", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantCode").value("acme"))
                .andExpect(jsonPath("$.role").value("FACILITATOR"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String token = json.readTree(body).get("token").asText();

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantCode").value("acme"))
                .andExpect(jsonPath("$.tenantId").value((int) acme));
    }

    @Test
    void wrong_password_is_401_without_leaking_whether_the_account_exists() throws Exception {
        long acme = seedTenant("acme", "Acme Corp");
        seedUser(acme, "trainer@acme.test", PASSWORD, "FACILITATOR");

        String wrong = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login("acme", "trainer@acme.test", "nope")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknown = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login("acme", "ghost@acme.test", "nope")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Same message for "bad password" and "no such user": no account enumeration.
        assertThat(json.readTree(wrong).path("detail").asText())
                .isEqualTo(json.readTree(unknown).path("detail").asText());
    }

    @Test
    void unknown_tenant_is_401() throws Exception {
        seedTenant("acme", "Acme Corp");
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login("globex", "trainer@acme.test", PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_without_a_token_is_401() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void me_with_a_garbage_token_is_401() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void the_same_email_in_two_tenants_resolves_to_the_token_tenant() throws Exception {
        long acme = seedTenant("acme", "Acme Corp");
        long globex = seedTenant("globex", "Globex");
        seedUser(acme, "boss@shared.test", "acme-password", "ADMIN");
        seedUser(globex, "boss@shared.test", "globex-password", "PLAYER");

        String globexToken = json.readTree(mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login("globex", "boss@shared.test", "globex-password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("token").asText();

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + globexToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantCode").value("globex"))
                .andExpect(jsonPath("$.role").value("PLAYER"));

        // Acme's password must not open Globex's account with the same email.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login("globex", "boss@shared.test", "acme-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expired_or_tampered_tokens_are_rejected() throws Exception {
        long acme = seedTenant("acme", "Acme Corp");
        seedUser(acme, "trainer@acme.test", PASSWORD, "FACILITATOR");
        String token = json.readTree(mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login("acme", "trainer@acme.test", PASSWORD)))
                .andReturn().getResponse().getContentAsString()).get("token").asText();

        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + parts[2].substring(0, 4) + "AAAA";

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ fixtures

    private long seedTenant(String code, String name) {
        return jdbc.queryForObject(
                "insert into tenant (code, name) values (?, ?) returning id",
                Long.class, code, name);
    }

    private void seedUser(long tenantId, String email, String password, String role) {
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?)
                """, tenantId, email, encoder.encode(password), email.split("@")[0], role);
    }

    private String login(String tenant, String email, String password) {
        JsonNode node = json.createObjectNode()
                .put("tenantCode", tenant)
                .put("email", email)
                .put("password", password);
        return node.toString();
    }
}
