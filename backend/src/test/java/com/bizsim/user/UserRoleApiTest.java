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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Getting a builder account at all.
 *
 * <p>The whole setup workbench is behind the BUILDER role, and only an ADMIN may mint one (D24). If
 * that path does not work, every editing screen is unreachable in practice — which is exactly how it
 * was until the People form grew a role selector. These tests pin the path, and that a facilitator
 * cannot use it to mint the person who will rewrite next term's economy.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserRoleApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private long acme;
    private String adminToken;
    private String facilitatorToken;

    @BeforeEach
    void seed() throws Exception {
        for (String table : List.of("round_statement", "round_result", "decision", "round", "player",
                "company_product", "company_staff", "company_rnd", "team", "run", "simulation")) {
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

        acme = jdbc.queryForObject(
                "insert into tenant (code, name) values ('acme', 'Acme') returning id", Long.class);
        user("owner@acme.test", "ADMIN");
        user("trainer@acme.test", "FACILITATOR");
        adminToken = login("owner@acme.test");
        facilitatorToken = login("trainer@acme.test");
    }

    @Test
    void an_admin_mints_a_builder_who_can_then_use_the_setup_screens() throws Exception {
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", "builder@acme.test", "displayName", "Builder",
                                "password", "builder1234", "role", "BUILDER"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("BUILDER"));

        // The point of the account: the setup screens open and a template can be copied and edited.
        String builderToken = loginWith("builder@acme.test", "builder1234");
        mvc.perform(get("/api/templates").header("Authorization", "Bearer " + builderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("standard-v1"));
        mvc.perform(post("/api/templates/standard-v1/copy")
                        .header("Authorization", "Bearer " + builderToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // ...and that the builder still cannot run a session.
        mvc.perform(get("/api/runs").header("Authorization", "Bearer " + builderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void a_facilitator_cannot_mint_staff() throws Exception {
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", "sneaky@acme.test", "displayName", "Sneaky",
                                "password", "sneaky1234", "role", "BUILDER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("user.facilitator_needs_admin"));

        // Adding a trainee stays a facilitator's normal job.
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", "ada@acme.test", "displayName", "Ada",
                                "password", "trainee1234", "role", "PLAYER"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("PLAYER"));
    }

    @Test
    void an_unknown_role_is_refused() throws Exception {
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", "x@acme.test", "displayName", "X",
                                "password", "password123", "role", "GOD"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("user.role_invalid"));
    }

    private void user(String email, String role) {
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?)
                """, acme, email, encoder.encode("pw-" + email), email, role);
    }

    private String loginWith(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", "acme", "email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
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
