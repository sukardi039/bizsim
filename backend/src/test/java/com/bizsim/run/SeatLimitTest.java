package com.bizsim.run;

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D118: how many companies a class may have is the package's number, not a constant.
 *
 * <p>Ten came from decision 5 — a sizing target for v1 — and was written into a validation, a builder
 * constant and a palette. The class size is a teaching decision, so the package states it; the console
 * holds the ceiling (thirty), because the palette and the position map have to stay readable.
 *
 * <p>What this asserts, in the order a builder would meet it: a package can be published for more than
 * ten companies; a package whose list is larger than its own limit is refused at publish, not at the
 * facilitator's "start"; the ceiling is a refusal, not a silent clamp; and the limit is readable back.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SeatLimitTest extends PostgresTestBase {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    private long tenant;
    private String builder;
    private String facilitator;
    private List<Long> profiles;

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

        tenant = jdbc.queryForObject(
                "insert into tenant (code, name) values ('acme', 'Acme') returning id", Long.class);
        user("builder@acme.test", "BUILDER");
        user("trainer@acme.test", "FACILITATOR");
        builder = login("builder@acme.test");
        facilitator = login("trainer@acme.test");

        profiles = new ArrayList<>();
        for (int i = 1; i <= 31; i++) {
            final int number = i;
            profiles.add(jdbc.queryForObject("""
                    insert into company_profile (tenant_id, name, company_name, products)
                    values (?, ?, ?, '["P1","P2"]'::jsonb) returning id
                    """, Long.class, tenant, "Company " + number, "Company " + number + " Ltd"));
        }
    }

    @Test
    void a_package_states_its_class_size_and_the_console_holds_the_ceiling() throws Exception {
        // Twelve companies in a package that says twelve: published, and the number reads back.
        MvcResult created = mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Twelve",
                                "templateKey", "standard-v1",
                                "locale", "en",
                                "companyProfileIds", profiles.subList(0, 12),
                                "seatLimit", 12, "facilitatorId", jdbc.queryForObject("select id from app_user where email = 'trainer@acme.test'", Long.class)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seatLimit").value(12))
                .andReturn();
        long packageId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(get("/api/packages").header("Authorization", "Bearer " + facilitator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seatLimit").value(12));

        // A package whose own list is larger than its limit is refused at publish — the builder's
        // moment, not the facilitator's.
        mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Overfull",
                                "templateKey", "standard-v1",
                                "locale", "en",
                                "companyProfileIds", profiles.subList(0, 13),
                                "seatLimit", 12, "facilitatorId", jdbc.queryForObject("select id from app_user where email = 'trainer@acme.test'", Long.class)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("package.too_many_companies"));

        // Beyond the console's ceiling is a refusal, not a clamp.
        mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Too big",
                                "templateKey", "standard-v1",
                                "locale", "en",
                                "companyProfileIds", profiles.subList(0, 31),
                                "seatLimit", 31, "facilitatorId", jdbc.queryForObject("select id from app_user where email = 'trainer@acme.test'", Long.class)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("package.seat_limit_invalid"));

        // A session started from the twelve-seat package seats twelve companies.
        MvcResult session = mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Twelve class"))))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(json.readTree(session.getResponse().getContentAsString()).get("teams").size())
                .as("twelve companies compete")
                .isEqualTo(12);
    }

    @Test
    void a_run_without_a_package_is_bounded_by_the_console_ceiling() throws Exception {
        long facilitatorId = jdbc.queryForObject(
                "select id from app_user where email = 'trainer@acme.test'", Long.class);

        // Thirty is allowed...
        mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(facilitatorId))
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Thirty",
                                "scenarioKey", "standard-v1",
                                "teams", teams(30)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.teams.length()").value(30));

        // ...thirty-one is not, and the refusal carries the ceiling rather than a bare failure.
        mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(facilitatorId))
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Thirty-one",
                                "scenarioKey", "standard-v1",
                                "teams", teams(31)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("run.too_many_teams"))
                .andExpect(jsonPath("$.params.max").value(30));
    }

    private List<Map<String, Object>> teams(int count) {
        List<Map<String, Object>> teams = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            teams.add(Map.of("name", "Team " + i, "companyName", "Company " + i + " Ltd"));
        }
        return teams;
    }

    private void user(String email, String role) {
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?)
                """, tenant, email, encoder.encode("pw-" + email), email, role);
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
