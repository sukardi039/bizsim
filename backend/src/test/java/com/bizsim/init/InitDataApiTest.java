package com.bizsim.init;

import com.bizsim.support.PostgresTestBase;
import com.bizsim.web.ProblemException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Init data: a tenant a class can be run in, created from an empty database.
 *
 * <p>The property under test is not "rows were written". It is that the database it produces is one
 * the product can actually use: the accounts sign in, the package is published with a frozen template
 * and its seats, the session exists, and its rounds have been computed by the engine into real
 * statements. A seeder that writes plausible-looking rows the console cannot render is the failure
 * this test exists to catch.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InitDataApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private InitDataService initData;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * The state a fresh installation is in: migrations have run (so the shipped template, its
     * catalogue and the global company profile exist) and nothing tenant-owned exists yet.
     */
    @BeforeEach
    void emptyDatabase() {
        for (String table : List.of("round_statement", "round_result", "decision", "round", "player",
                "company_product", "company_staff", "company_rnd", "team", "run", "simulation")) {
            jdbc.update("delete from " + table);
        }
        // scenario.frozen_for_package -> package, and package.template_key -> scenario: a cycle, so
        // it is broken explicitly before either side is deleted.
        jdbc.update("update scenario set frozen_for_package = null");
        jdbc.update("delete from simulation_package_part");
        jdbc.update("delete from simulation_package");
        jdbc.update("delete from scenario where key like 'pkg-%' or tenant_id is not null");
        jdbc.update("delete from company_profile");
        jdbc.update("delete from app_user");
        jdbc.update("delete from tenant");
        jdbc.update("""
                insert into company_profile (tenant_id, name, company_name, products, notes)
                values (null, 'Standard manufacturer', '', '[]'::jsonb, 'shipped')
                """);
    }

    @Test
    void from_an_empty_database_it_creates_a_class_that_has_been_played() throws Exception {
        InitDataService.Summary summary = initData.init("pilot",
                new InitDataService.Params(3, 3, 2, null, "en", "pilot-pass-1", null, null, null));

        // ---- the accounts exist, with the roles the two-party model needs
        assertEquals(1, count("select count(*) from app_user where role = 'ADMIN'"));
        assertEquals(1, count("select count(*) from app_user where role = 'BUILDER'"));
        assertEquals(1, count("select count(*) from app_user where role = 'FACILITATOR'"));
        assertEquals(3, count("select count(*) from app_user where role = 'PLAYER'"));

        // ---- a tenant-owned template to edit, and one profile per company
        assertEquals(1, count("select count(*) from scenario where tenant_id is not null and status = 'DRAFT'"));
        assertEquals(3, count("select count(*) from company_profile where tenant_id is not null"));

        // ---- the package is published: frozen template, one part, three seats
        assertEquals(1, count("select count(*) from simulation_package"));
        assertEquals(1, count("select count(*) from simulation_package_part"));
        assertEquals(3, count("select count(*) from simulation_package_seat"));
        assertEquals("en", jdbc.queryForObject(
                "select locale from simulation_package where id = ?", String.class, summary.packageId()));

        // ---- the session exists, ran two rounds, and is finished
        assertEquals("FINISHED", summary.runStatus());
        assertEquals(2, summary.roundsComputed());
        assertEquals(2, count("select count(*) from round where status = 'COMPUTED'"));
        assertEquals(6, count("select count(*) from round_result"));
        assertEquals(6, count("select count(*) from decision"));
        assertEquals("FINISHED", jdbc.queryForObject(
                "select status from run where id = ?", String.class, summary.runId()));
        assertEquals("en", jdbc.queryForObject(
                "select lang from run where id = ?", String.class, summary.runId()));

        // ---- the engine produced real numbers, not placeholders
        BigDecimal revenue = jdbc.queryForObject(
                "select coalesce(sum(revenue), 0) from round_statement", BigDecimal.class);
        assertThat(revenue.signum()).as("somebody sold something").isGreaterThan(0);
        assertEquals(6, count("select count(distinct profit) from round_statement"));
        assertThat(count("select count(*) from round_statement where profit <> 0"))
                .as("profits are computed, not zeroed").isGreaterThan(0);

        // ---- and the product's own reads see it: the class list, the run list, the standings
        String admin = login("pilot", "admin@pilot.test", "pilot-pass-1");
        mvc.perform(get("/api/packages").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].parts.length()").value(1))
                .andExpect(jsonPath("$[0].seats.length()").value(3));
        mvc.perform(get("/api/runs").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("FINISHED"));
        mvc.perform(get("/api/runs/" + summary.runId() + "/standings")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
    }

    @Test
    void the_seeded_accounts_can_sign_in_and_their_tenant_is_the_class_language() {
        initData.init("pilot", new InitDataService.Params(2, 2, 1, null, "zh", "pilot-pass-1",
                null, null, null));

        // every seeded account is real: the credential works and the tenant code is the login code
        assertThat(login("pilot", "trainer@pilot.test", "pilot-pass-1")).isNotBlank();
        assertThat(login("pilot", "player1@pilot.test", "pilot-pass-1")).isNotBlank();
        assertEquals("zh", jdbc.queryForObject(
                "select locale from tenant where code = 'pilot'", String.class));
    }

    @Test
    void a_tenant_that_already_has_a_package_is_refused_rather_than_appended_to() {
        InitDataService.Params params = new InitDataService.Params(2, 2, 1, null, "en",
                "pilot-pass-1", null, null, null);
        initData.init("pilot", params);

        ProblemException refusal = assertThrows(ProblemException.class,
                () -> initData.init("pilot", params));
        assertEquals("init.already_present", refusal.problem().code());
        assertEquals(HttpStatus.CONFLICT, refusal.getStatusCode());
        // nothing was appended by the refused call
        assertEquals(1, count("select count(*) from simulation_package"));
        assertEquals(1, count("select count(*) from run"));
    }

    @Test
    void the_endpoint_creates_the_data_for_the_callers_tenant_and_is_admin_only() throws Exception {
        long acme = tenant("acme");
        user(acme, "boss@acme.test", "ADMIN");
        user(acme, "ada@acme.test", "PLAYER");
        String admin = login("acme", "boss@acme.test", "pw-boss@acme.test");
        String player = login("acme", "ada@acme.test", "pw-ada@acme.test");

        mvc.perform(post("/api/admin/init-data").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/init-data")
                        .header("Authorization", "Bearer " + player)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("init.admin_only"));

        MvcResult created = mvc.perform(post("/api/admin/init-data")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "companies", 2, "trainees", 2, "rounds", 1, "locale", "en",
                                "password", "acme-pass-1", "packageName", "Autumn cohort"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantCode").value("acme"))
                .andExpect(jsonPath("$.packageName").value("Autumn cohort"))
                .andExpect(jsonPath("$.runStatus").value("FINISHED"))
                .andExpect(jsonPath("$.roundsComputed").value(1))
                .andReturn();
        JsonNode body = json.readTree(created.getResponse().getContentAsString());
        assertThat(body.get("runId").asLong()).isPositive();

        // a second attempt is a conflict, not a second class
        mvc.perform(post("/api/admin/init-data")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("init.already_present"));
    }

    // ------------------------------------------------------------------ helpers

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private long tenant(String code) {
        return jdbc.queryForObject("insert into tenant (code, name) values (?, ?) returning id",
                Long.class, code, code);
    }

    private void user(long tenantId, String email, String role) {
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?)
                """, tenantId, email, encoder.encode("pw-" + email), email, role);
    }

    private String login(String tenantCode, String email, String password) {
        try {
            MvcResult result = mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "tenantCode", tenantCode, "email", email, "password", password))))
                    .andExpect(status().isOk())
                    .andReturn();
            return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
