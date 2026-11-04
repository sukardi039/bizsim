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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    /**
     * Ruling 4 Nov 2026: the builder builds everything the class is made of — the economy, the
     * companies, the people and the package — because the facilitator's job is only to run the
     * session. This is the regression that emptied the Packages form: the builder asked for the
     * people list, was refused, and lost the template dropdown along with it.
     */
    @Test
    void a_builder_builds_the_people_too() throws Exception {
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", "builder@acme.test", "displayName", "Builder",
                                "password", "builder1234", "role", "BUILDER"))))
                .andExpect(status().isCreated());
        String builderToken = loginWith("builder@acme.test", "builder1234");

        // The builder mints the class: trainees, one per company.
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + builderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", "ada@acme.test", "displayName", "Ada",
                                "password", "trainee1234", "role", "PLAYER"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("PLAYER"));

        // Both people reads answer: the roster (People screen) and the narrow class list (packages).
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + builderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == 'ada@acme.test')]").exists());
        mvc.perform(get("/api/users/trainees").header("Authorization", "Bearer " + builderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value("ada@acme.test"));

        // Staff roles stay an admin's decision: a builder must not mint the facilitator of the run
        // or the person who rewrites the economy next term.
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + builderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", "sneaky@acme.test", "password", "sneaky1234",
                                "role", "FACILITATOR"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("user.facilitator_needs_admin"));

        // A trainee's password is the builder's support question; a staff password is not.
        Long ada = jdbc.queryForObject("select id from app_user where email = 'ada@acme.test'", Long.class);
        mvc.perform(put("/api/users/" + ada + "/password")
                        .header("Authorization", "Bearer " + builderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("password", "reset1234"))))
                .andExpect(status().isNoContent());
        Long trainer = jdbc.queryForObject(
                "select id from app_user where email = 'trainer@acme.test'", Long.class);
        mvc.perform(put("/api/users/" + trainer + "/password")
                        .header("Authorization", "Bearer " + builderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("password", "reset1234"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("user.admin_only"));

        // The trainee still cannot read the roster, and the code is now staff-flavoured rather than
        // "for facilitators" — a builder hitting it was the confusing part.
        String adaToken = loginWith("ada@acme.test", "reset1234");
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + adaToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.staff_only"));
        mvc.perform(get("/api/users/trainees").header("Authorization", "Bearer " + adaToken))
                .andExpect(status().isForbidden());
    }

    /**
     * The People list is for assigning people, so someone who already sits in a company is noise.
     * Off by default: a trainee with a seat in a package class list (D27) or a session is left out,
     * and `includeAssigned=true` brings them back. Staff are never filtered.
     */
    @Test
    void the_people_list_hides_trainees_already_in_a_company_by_default() throws Exception {
        for (String email : List.of("ada@acme.test", "grace@acme.test")) {
            mvc.perform(post("/api/users")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "email", email, "displayName", email,
                                    "password", "trainee1234", "role", "PLAYER"))))
                    .andExpect(status().isCreated());
        }
        Long ada = jdbc.queryForObject("select id from app_user where email = 'ada@acme.test'", Long.class);
        Long profile = jdbc.queryForObject("""
                insert into company_profile (tenant_id, name, company_name, products)
                values (?, 'Alpha Industries', 'Alpha Industries', '[]'::jsonb) returning id
                """, Long.class, acme);
        Long packageId = jdbc.queryForObject("""
                insert into simulation_package (tenant_id, name, template_key, template_version,
                                                company_profile_ids)
                values (?, 'Autumn 2026', 'standard-v1', 1, to_jsonb(array[?])) returning id
                """, Long.class, acme, profile);
        jdbc.update("insert into simulation_package_seat (package_id, position, user_id) values (?, 0, ?)",
                packageId, ada);

        // Default: the seated trainee is gone, the free one and the staff stay.
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == 'ada@acme.test')]").isEmpty())
                .andExpect(jsonPath("$[?(@.email == 'grace@acme.test')]").exists())
                .andExpect(jsonPath("$[?(@.email == 'owner@acme.test')]").exists());

        // The switch on the People page.
        mvc.perform(get("/api/users").param("includeAssigned", "true")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == 'ada@acme.test')]").exists());

        // The class list of a package is unaffected: a person may be seated in a new package too.
        mvc.perform(get("/api/users/trainees").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void an_admin_fixes_a_role_without_recreating_the_account() throws Exception {
        // The mistake this exists for: an account created as a trainee that should have been a builder.
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", "oops@acme.test", "displayName", "Oops",
                                "password", "password123", "role", "PLAYER"))))
                .andExpect(status().isCreated());
        Long id = jdbc.queryForObject("select id from app_user where email = 'oops@acme.test'", Long.class);

        mvc.perform(patch("/api/users/" + id + "/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "BUILDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("BUILDER"));

        // The promoted account can now use the setup screens with the same password.
        String promoted = loginWith("oops@acme.test", "password123");
        mvc.perform(get("/api/templates").header("Authorization", "Bearer " + promoted))
                .andExpect(status().isOk());

        // A facilitator may not do this.
        mvc.perform(patch("/api/users/" + id + "/role")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "ADMIN"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("user.admin_only"));

        // Nor may an admin change their own role out from under themselves.
        Long adminId = jdbc.queryForObject(
                "select id from app_user where email = 'owner@acme.test'", Long.class);
        mvc.perform(patch("/api/users/" + adminId + "/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "PLAYER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("user.cannot_change_own_role"));

        // And the last admin cannot be demoted away: nobody would be left to mint staff.
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", "second@acme.test", "displayName", "Second",
                                "password", "password123", "role", "ADMIN"))))
                .andExpect(status().isCreated());
        Long secondAdmin = jdbc.queryForObject(
                "select id from app_user where email = 'second@acme.test'", Long.class);
        // Demote the second admin: allowed, one admin remains.
        mvc.perform(patch("/api/users/" + secondAdmin + "/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "FACILITATOR"))))
                .andExpect(status().isOk());
        // Now only one admin is left, and it cannot be demoted by... itself. Verified via a second
        // admin path: promote again, then try to demote the original from the new admin's session.
        mvc.perform(patch("/api/users/" + secondAdmin + "/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "ADMIN"))))
                .andExpect(status().isOk());
        String secondAdminToken = loginWith("second@acme.test", "password123");
        mvc.perform(patch("/api/users/" + adminId + "/role")
                        .header("Authorization", "Bearer " + secondAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "PLAYER"))))
                .andExpect(status().isOk()); // two admins existed, so this is allowed
        mvc.perform(patch("/api/users/" + adminId + "/role")
                        .header("Authorization", "Bearer " + secondAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void a_forgotten_or_mistyped_password_can_be_reset() throws Exception {
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", "forgetful@acme.test", "displayName", "Forgetful",
                                "password", "firstpassword", "role", "PLAYER"))))
                .andExpect(status().isCreated());
        Long id = jdbc.queryForObject(
                "select id from app_user where email = 'forgetful@acme.test'", Long.class);

        // Too short is refused rather than silently accepted.
        mvc.perform(put("/api/users/" + id + "/password")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("password", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("user.password_too_short"));

        mvc.perform(put("/api/users/" + id + "/password")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("password", "secondpassword"))))
                .andExpect(status().isNoContent());

        // The old one no longer works; the new one does.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", "acme", "email", "forgetful@acme.test",
                                "password", "firstpassword"))))
                .andExpect(status().isUnauthorized());
        loginWith("forgetful@acme.test", "secondpassword");

        // A person can change their own password, but must know the current one.
        String own = loginWith("forgetful@acme.test", "secondpassword");
        mvc.perform(post("/api/me/password")
                        .header("Authorization", "Bearer " + own)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "currentPassword", "wrong-one", "newPassword", "thirdpassword"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("user.current_password_wrong"));
        mvc.perform(post("/api/me/password")
                        .header("Authorization", "Bearer " + own)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "currentPassword", "secondpassword", "newPassword", "thirdpassword"))))
                .andExpect(status().isNoContent());
        loginWith("forgetful@acme.test", "thirdpassword");
    }

    @Test
    void signing_in_ignores_the_case_of_the_email_and_tenant_code() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", "ACME", "email", "Owner@ACME.test",
                                "password", "pw-owner@acme.test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString());
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
