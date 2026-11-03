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
 * S6e: a session started from what was prepared — template + company profiles + roster.
 *
 * <p>The property that matters is that the three pieces agree: the company the facilitator picked is
 * the company that appears, carrying the product line its profile says, with its trainee already
 * seated — and that a mismatch is refused before anything is written.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PreparedSessionApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private long acme;
    private long other;
    private String facilitator;
    private String builder;
    private String player;
    private String otherFacilitator;

    @BeforeEach
    void seed() throws Exception {
        for (String table : List.of("round_statement", "round_result", "decision", "round", "player",
                "company_product", "company_staff", "company_rnd", "team", "run", "simulation",
                "app_user", "company_profile", "tenant")) {
            jdbc.update("delete from " + table);
        }
        // The shipped profile the migration seeds.
        jdbc.update("""
                insert into company_profile (tenant_id, name, company_name, products, notes)
                values (null, 'Standard manufacturer', '', '[]'::jsonb, 'shipped')
                """);

        acme = tenant("acme");
        other = tenant("other");
        user(acme, "trainer@acme.test", "FACILITATOR");
        user(acme, "builder@acme.test", "BUILDER");
        user(acme, "ada@acme.test", "PLAYER");
        user(acme, "budi@acme.test", "PLAYER");
        user(other, "trainer@other.test", "FACILITATOR");
        facilitator = login("acme", "trainer@acme.test");
        builder = login("acme", "builder@acme.test");
        player = login("acme", "ada@acme.test");
        otherFacilitator = login("other", "trainer@other.test");
    }

    @Test
    void the_shipped_profile_is_visible_and_a_copy_is_the_one_you_edit() throws Exception {
        mvc.perform(get("/api/companies").header("Authorization", "Bearer " + facilitator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Standard manufacturer"))
                .andExpect(jsonPath("$[0].tenantId").doesNotExist());

        MvcResult copied = mvc.perform(post("/api/companies/" + profileId("Standard manufacturer") + "/copy")
                        .header("Authorization", "Bearer " + builder))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value((int) acme))
                .andReturn();
        long copyId = json.readTree(copied.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(put("/api/companies/" + copyId)
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Alpha Industries", "companyName", "Alpha Industries",
                                "products", List.of("P1", "P2"), "notes", "premium line"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alpha Industries"))
                .andExpect(jsonPath("$.products.length()").value(2));

        // The shipped profile itself stays read-only.
        mvc.perform(put("/api/companies/" + profileId("Standard manufacturer"))
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Mine", "products", List.of()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("company_profile.read_only"));

        // Another tenant cannot see the copy.
        mvc.perform(get("/api/companies").header("Authorization", "Bearer " + otherFacilitator))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void a_session_starts_from_a_template_profiles_and_trainees() throws Exception {
        long alpha = profile("Alpha Industries", "Alpha Industries", List.of("P1", "P2"));
        long beta = profile("Beta Corp", "Beta Corp", List.of());
        long ada = userId("ada@acme.test");
        long budi = userId("budi@acme.test");

        MvcResult started = mvc.perform(post("/api/runs/prepared")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Autumn cohort", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(alpha, beta),
                                "traineeIds", List.of(ada, budi)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.teams.length()").value(2))
                .andExpect(jsonPath("$.players.length()").value(2))
                .andReturn();

        JsonNode run = json.readTree(started.getResponse().getContentAsString());
        long runId = run.get("id").asLong();
        long alphaTeam = run.get("teams").get(0).get("id").asLong();
        long betaTeam = run.get("teams").get(1).get("id").asLong();

        // The companies are the ones that were picked, not names typed into a blank run.
        assertThat(jdbc.queryForObject("select company_name from team where id = ?",
                String.class, alphaTeam)).isEqualTo("Alpha Industries");

        // The profile's product line narrows what the company sells; an empty line means "all".
        assertThat(jdbc.queryForList(
                "select product_code from company_product where team_id = ? order by product_code",
                String.class, alphaTeam)).containsExactly("P1", "P2");
        assertThat(jdbc.queryForObject(
                "select count(*) from company_product where team_id = ?", Integer.class, betaTeam))
                .isEqualTo(count("product", "standard-v1"));

        // The trainees are seated, in order, and can already read their own company.
        assertThat(jdbc.queryForObject(
                "select count(*) from player where run_id = ? and user_id = ?", Integer.class, runId, ada))
                .isEqualTo(1);
        mvc.perform(get("/api/runs/" + runId + "/teams/" + alphaTeam + "/context")
                        .header("Authorization", "Bearer " + player))
                .andExpect(status().isOk());
        // ...and their rival's company is theirs to look at, not hers.
        mvc.perform(get("/api/runs/" + runId + "/teams/" + betaTeam + "/context")
                        .header("Authorization", "Bearer " + player))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/runs/" + runId).header("Authorization", "Bearer " + facilitator))
                .andExpect(status().isOk());
    }

    @Test
    void a_prepared_session_is_refused_before_anything_is_written() throws Exception {
        long wrongProducts = profile("Wrong line", "Wrong line", List.of("P9"));

        mvc.perform(post("/api/runs/prepared")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Bad", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(wrongProducts),
                                "traineeIds", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("run.unknown_product"))
                .andExpect(jsonPath("$.params.product").value("P9"));

        long alpha = profile("Alpha", "Alpha", List.of());
        mvc.perform(post("/api/runs/prepared")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Bad", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(alpha),
                                "traineeIds", List.of(userId("ada@acme.test"), userId("budi@acme.test"))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("run.too_many_trainees"));

        // A profile from another tenant is invisible, not a cross-tenant hole.
        long foreign = profileFor(other, "Foreign", "Foreign", List.of());
        mvc.perform(post("/api/runs/prepared")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Bad", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(foreign), "traineeIds", List.of()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("company_profile.not_found"));

        // Nothing was written by any of the three refusals.
        assertThat(jdbc.queryForObject("select count(*) from run", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from simulation", Integer.class)).isZero();
    }

    @Test
    void profiles_are_facilitator_material() throws Exception {
        mvc.perform(get("/api/companies").header("Authorization", "Bearer " + player))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/runs/prepared")
                        .header("Authorization", "Bearer " + player)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Bad", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(), "traineeIds", List.of()))))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private long profile(String name, String companyName, List<String> products) {
        return profileFor(acme, name, companyName, products);
    }

    private long profileFor(long tenantId, String name, String companyName, List<String> products) {
        String codes;
        try {
            codes = json.writeValueAsString(products);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return jdbc.queryForObject("""
                insert into company_profile (tenant_id, name, company_name, products)
                values (?, ?, ?, ?::jsonb) returning id
                """, Long.class, tenantId, name, companyName, codes);
    }

    private long profileId(String name) {
        return jdbc.queryForObject(
                "select id from company_profile where name = ? and tenant_id is null", Long.class, name);
    }

    private long userId(String email) {
        return jdbc.queryForObject("select id from app_user where email = ?", Long.class, email);
    }

    private int count(String table, String key) {
        return jdbc.queryForObject("select count(*) from " + table + " where scenario_key = ?",
                Integer.class, key);
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
