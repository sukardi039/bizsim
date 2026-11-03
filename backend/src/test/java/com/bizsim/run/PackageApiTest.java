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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Building and running are two jobs (ruling 3 Nov 2026).
 *
 * <p>A **builder** publishes a package — a template plus the companies that will compete — and a
 * **facilitator** starts sessions from it without being able to change what is inside. The tests pin
 * both halves of that promise: the permissions, and the immutability (a package records the template
 * version it was built on, so a later edit cannot reach back into a class already sold to it).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PackageApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private long acme;
    private long other;
    private String builder;
    private String facilitator;
    private String otherFacilitator;
    private String player;
    private long profileId;

    @BeforeEach
    void seed() throws Exception {
        for (String table : List.of("round_statement", "round_result", "decision", "round", "player",
                "company_product", "company_staff", "company_rnd", "team", "run", "simulation")) {
            jdbc.update("delete from " + table);
        }
        // scenario.frozen_for_package -> package, and package.template_key -> scenario: a cycle, so
        // it is broken explicitly before either side is deleted.
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

        acme = tenant("acme");
        other = tenant("other");
        user(acme, "builder@acme.test", "BUILDER");
        user(acme, "trainer@acme.test", "FACILITATOR");
        user(acme, "ada@acme.test", "PLAYER");
        user(other, "trainer@other.test", "FACILITATOR");
        builder = login("acme", "builder@acme.test");
        facilitator = login("acme", "trainer@acme.test");
        otherFacilitator = login("other", "trainer@other.test");
        player = login("acme", "ada@acme.test");

        profileId = jdbc.queryForObject("""
                insert into company_profile (tenant_id, name, company_name, products)
                values (?, 'Alpha Industries', 'Alpha Industries', '["P1","P2"]'::jsonb) returning id
                """, Long.class, acme);
    }

    @Test
    void a_builder_publishes_and_a_facilitator_consumes() throws Exception {
        long packageId = publish("Autumn 2026");

        // The builder's package is visible to the facilitator, who cannot mint one.
        mvc.perform(get("/api/packages").header("Authorization", "Bearer " + facilitator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Autumn 2026"))
                .andExpect(jsonPath("$[0].templateVersion").value(1));
        mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Mine", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(profileId)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.builder_only"));

        // ...and the builder cannot run one.
        mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Nope"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.facilitator_only"));
    }

    @Test
    void a_session_is_started_from_the_package_and_records_it() throws Exception {
        long packageId = publish("Autumn 2026");
        long ada = jdbc.queryForObject("select id from app_user where email = 'ada@acme.test'", Long.class);

        MvcResult started = mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Monday class", "traineeIds", List.of(ada)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.teams.length()").value(1))
                .andExpect(jsonPath("$.players.length()").value(1))
                .andReturn();

        long runId = json.readTree(started.getResponse().getContentAsString()).get("id").asLong();
        long teamId = json.readTree(started.getResponse().getContentAsString())
                .get("teams").get(0).get("id").asLong();

        assertThat(jdbc.queryForObject("select company_name from team where id = ?", String.class, teamId))
                .isEqualTo("Alpha Industries");
        assertThat(jdbc.queryForObject("select package_id from run where id = ?", Long.class, runId))
                .isEqualTo(packageId);
        assertThat(jdbc.queryForList(
                "select product_code from company_product where team_id = ? order by product_code",
                String.class, teamId)).containsExactly("P1", "P2");

        // The seated trainee lands on her company.
        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamId + "/context")
                        .header("Authorization", "Bearer " + player))
                .andExpect(status().isOk());
    }

    @Test
    void publishing_freezes_a_copy_so_a_later_edit_cannot_reach_the_class() throws Exception {
        // A tenant draft the builder can edit, published as a package.
        String draft = copyTemplate();
        long packageId = publishFrom(draft, "Frozen package");
        String frozenKey = jdbc.queryForObject(
                "select template_key from simulation_package where id = ?", String.class, packageId);
        assertThat(frozenKey).startsWith("pkg-");
        assertThat(jdbc.queryForObject("select source_template_key from simulation_package where id = ?",
                String.class, packageId)).isEqualTo(draft);

        // The builder edits the draft afterwards: 8 rounds become 6, catalogue untouched.
        mvc.perform(put("/api/templates/" + draft + "/settings")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("rounds", 6))))
                .andExpect(status().isOk());

        // The class runs on the frozen copy: it still has the eight rounds it was published with.
        long ada = jdbc.queryForObject("select id from app_user where email = 'ada@acme.test'", Long.class);
        MvcResult started = mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Monday class", "traineeIds", List.of(ada)))))
                .andExpect(status().isCreated())
                .andReturn();
        long runId = json.readTree(started.getResponse().getContentAsString()).get("id").asLong();

        assertThat(jdbc.queryForObject("select scenario_key from run where id = ?", String.class, runId))
                .isEqualTo(frozenKey);
        assertThat(jdbc.queryForObject("select definition->>'rounds' from scenario where key = ?",
                String.class, frozenKey)).isEqualTo("8");
        assertThat(jdbc.queryForObject("select definition->>'rounds' from scenario where key = ?",
                String.class, draft)).isEqualTo("6");

        // The frozen copy is not a template a builder can pick or edit.
        assertThat(jdbc.queryForObject("""
                select count(*) from scenario s join simulation_package p on p.id = s.frozen_for_package
                where s.key = ?
                """, Integer.class, frozenKey)).isEqualTo(1);
        mvc.perform(put("/api/templates/" + frozenKey + "/settings")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("rounds", 3))))
                .andExpect(status().isNotFound());

        // Re-publishing the edited draft makes a *new* package on a *new* frozen copy.
        long second = publishFrom(draft, "Second cohort");
        String secondFrozen = jdbc.queryForObject(
                "select template_key from simulation_package where id = ?", String.class, second);
        assertThat(secondFrozen).isNotEqualTo(frozenKey);
        assertThat(jdbc.queryForObject("select definition->>'rounds' from scenario where key = ?",
                String.class, secondFrozen)).isEqualTo("6");
    }

    @Test
    void a_retired_package_cannot_start_a_session() throws Exception {
        long packageId = publish("Old cohort");

        mvc.perform(post("/api/packages/" + packageId + "/retire")
                        .header("Authorization", "Bearer " + builder))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Late class"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("package.retired"));

        assertThat(jdbc.queryForObject("select count(*) from run", Integer.class)).isZero();
    }

    @Test
    void another_tenants_package_is_invisible() throws Exception {
        long packageId = publish("Autumn 2026");

        mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + otherFacilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Not mine"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("package.not_found"));
        mvc.perform(get("/api/packages").header("Authorization", "Bearer " + otherFacilitator))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ------------------------------------------------------------------ helpers

    private long publish(String name) throws Exception {
        return publishFrom("standard-v1", name);
    }

    private long publishFrom(String templateKey, String name) throws Exception {
        MvcResult published = mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", name, "templateKey", templateKey,
                                "companyProfileIds", List.of(profileId)))))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(published.getResponse().getContentAsString()).get("id").asLong();
    }

    private String copyTemplate() throws Exception {
        MvcResult copied = mvc.perform(post("/api/templates/standard-v1/copy")
                        .header("Authorization", "Bearer " + builder))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = json.readTree(copied.getResponse().getContentAsString());
        return body.get("key").asText();
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

    private String login(String tenantCode, String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", tenantCode, "email", email, "password", "pw-" + email))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
