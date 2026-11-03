package com.bizsim.web;

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

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Language resolution (V10): the person's choice, else the tenant default, else English.
 *
 * <p>The server returns a code, never translated text, so these tests are about the value that
 * reaches the client — the catalog is the client's business.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LocaleApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private long tenantId;
    private String alice;
    private String bob;

    @BeforeEach
    void seed() throws Exception {
        // Play data first, then accounts, then tenants: `player` and `simulation` reference
        // `app_user` without a cascade, so a plain "delete from tenant" fails once another test class
        // has left a run behind.
        for (String table : java.util.List.of("round_statement", "round_result", "decision", "round",
                "player", "company_product", "company_staff", "company_rnd", "team", "run",
                "simulation", "app_user", "tenant")) {
            jdbc.update("delete from " + table);
        }
        tenantId = jdbc.queryForObject(
                "insert into tenant (code, name) values ('acme', 'Acme') returning id", Long.class);
        user("alice@acme.test");
        user("bob@acme.test");
        alice = login("alice@acme.test");
        bob = login("bob@acme.test");
    }

    @Test
    void english_is_the_default_until_someone_says_otherwise() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("en"));
    }

    @Test
    void the_tenant_default_applies_to_a_person_who_has_not_chosen() throws Exception {
        jdbc.update("update tenant set locale = 'zh' where id = ?", tenantId);

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + alice))
                .andExpect(jsonPath("$.locale").value("zh"));

        // ...but a personal choice still wins over it.
        mvc.perform(put("/api/me/locale")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("locale", "en"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("en"));
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + alice))
                .andExpect(jsonPath("$.locale").value("en"));
    }

    @Test
    void a_person_changes_only_their_own_language() throws Exception {
        mvc.perform(put("/api/me/locale")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("locale", "zh"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("zh"));

        // Bob is unaffected: the endpoint takes no user id, only the token.
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + bob))
                .andExpect(jsonPath("$.locale").value("en"));
    }

    @Test
    void an_unknown_language_is_refused() throws Exception {
        mvc.perform(put("/api/me/locale")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("locale", "fr"))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + alice))
                .andExpect(jsonPath("$.locale").value("en"));

        mvc.perform(put("/api/me/locale"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private void user(String email) {
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, 'PLAYER')
                """, tenantId, email, encoder.encode("pw-" + email), email);
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", "acme", "email", email, "password", "pw-" + email))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }
}
