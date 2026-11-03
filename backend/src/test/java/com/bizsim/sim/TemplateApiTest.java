package com.bizsim.sim;

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
 * Templates (ruling R2): `standard-v1` is global and read-only; a tenant copies it and owns the copy.
 *
 * <p>The important property is that a copy is complete — the definition document *and* every
 * catalogue row. A copy that missed the product table would load with no products and only fail at
 * the first round, in a classroom.
 *
 * <p>This class never rewrites `standard-v1`: the migrations seed it and other test classes play on
 * it, so the assertions read its real shape instead of inventing one.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TemplateApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private long acme;
    private long other;
    private String acmeFacilitator;
    private String acmePlayer;
    private String otherFacilitator;

    @BeforeEach
    void seed() throws Exception {
        // Play data first: a test that creates a run leaves a simulation pointing at its template, and
        // `delete from scenario` then fails on simulation_scenario_key_fkey.
        for (String table : List.of("round_statement", "round_result", "decision", "round", "player",
                "company_product", "company_staff", "company_rnd", "team", "run", "simulation",
                "app_user", "tenant")) {
            jdbc.update("delete from " + table);
        }
        // Only tenant-owned templates; the shipped template stays.
        jdbc.update("delete from scenario where tenant_id is not null");

        acme = tenant("acme", "Acme");
        other = tenant("other", "Other");
        user(acme, "trainer@acme.test", "FACILITATOR");
        user(acme, "player@acme.test", "PLAYER");
        user(other, "trainer@other.test", "FACILITATOR");

        acmeFacilitator = login("acme", "trainer@acme.test");
        acmePlayer = login("acme", "player@acme.test");
        otherFacilitator = login("other", "trainer@other.test");
    }

    @Test
    void the_shipped_template_is_listed_and_global() throws Exception {
        mvc.perform(get("/api/templates").header("Authorization", "Bearer " + acmeFacilitator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].key").value("standard-v1"))
                .andExpect(jsonPath("$[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$[0].products").value(count("product", "standard-v1")))
                .andExpect(jsonPath("$[0].rounds").value(count("scenario_round", "standard-v1")));
    }

    @Test
    void copying_creates_a_complete_tenant_owned_draft_and_leaves_the_global_one_alone() throws Exception {
        int globalProducts = count("product", "standard-v1");
        int globalChannels = count("channel", "standard-v1");
        int globalRounds = count("scenario_round", "standard-v1");
        int globalProjects = count("rnd_project", "standard-v1");
        int globalEvents = count("environment_event", "standard-v1");

        MvcResult copied = mvc.perform(post("/api/templates/standard-v1/copy")
                        .header("Authorization", "Bearer " + acmeFacilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Acme spring cohort"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Acme spring cohort"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.basedOn").value("standard-v1"))
                .andExpect(jsonPath("$.tenantId").value((int) acme))
                .andReturn();
        String key = json.readTree(copied.getResponse().getContentAsString()).get("key").asText();
        assertThat(key).startsWith("acme-standard-v1");

        // Complete copy: same document, same rows in every catalogue table.
        assertThat(count("product", key)).isEqualTo(globalProducts);
        assertThat(count("channel", key)).isEqualTo(globalChannels);
        assertThat(count("scenario_round", key)).isEqualTo(globalRounds);
        assertThat(count("rnd_project", key)).isEqualTo(globalProjects);
        assertThat(count("environment_event", key)).isEqualTo(globalEvents);
        assertThat(jdbc.queryForObject(
                "select definition::text = (select definition::text from scenario where key = 'standard-v1') "
                        + "from scenario where key = ?", Boolean.class, key)).isTrue();

        // The global template is untouched and still the only row without a tenant.
        assertThat(jdbc.queryForObject(
                "select count(*) from scenario where tenant_id is null", Integer.class)).isEqualTo(1);
        assertThat(count("product", "standard-v1")).isEqualTo(globalProducts);

        // Visible to its own tenant, not to another one — and not copyable across tenants.
        mvc.perform(get("/api/templates").header("Authorization", "Bearer " + acmeFacilitator))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/templates").header("Authorization", "Bearer " + otherFacilitator))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/templates/" + key + "/copy")
                        .header("Authorization", "Bearer " + otherFacilitator))
                .andExpect(status().isNotFound());
    }

    @Test
    void copying_twice_makes_two_distinct_templates() throws Exception {
        mvc.perform(post("/api/templates/standard-v1/copy")
                        .header("Authorization", "Bearer " + acmeFacilitator))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(
                        org.hamcrest.Matchers.endsWith("(copy)")));
        mvc.perform(post("/api/templates/standard-v1/copy")
                        .header("Authorization", "Bearer " + acmeFacilitator))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject(
                "select count(distinct key) from scenario where tenant_id = ?", Integer.class, acme))
                .isEqualTo(2);
    }

    @Test
    void templates_are_facilitator_material() throws Exception {
        mvc.perform(get("/api/templates").header("Authorization", "Bearer " + acmePlayer))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/templates/standard-v1/copy")
                        .header("Authorization", "Bearer " + acmePlayer))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/templates"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void a_copy_can_be_edited_and_the_shipped_template_cannot() throws Exception {
        String key = copyStandard();

        mvc.perform(put("/api/templates/" + key + "/settings")
                        .header("Authorization", "Bearer " + acmeFacilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "rounds", 6, "startingCash", 1_500_000_000L, "cycles", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.settings.rounds").value(6))
                .andExpect(jsonPath("$.settings.startingCash").value(1_500_000_000L));

        // The merge keeps calibration the editor never shows: a form save must not drop the engine's
        // elasticity block or its baseline competitor.
        assertThat(jdbc.queryForObject(
                "select definition->>'baselineCompetitor' from scenario where key = ?", String.class, key))
                .isNotNull();
        assertThat(jdbc.queryForObject(
                "select definition->'elasticity'->>'price' from scenario where key = ?", String.class, key))
                .isNotNull();

        mvc.perform(put("/api/templates/" + key + "/environment")
                        .header("Authorization", "Bearer " + acmeFacilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("rounds", List.of(
                                Map.of("round", 1, "demandIndex", 1.0, "costIndex", 1.0, "wageIndex", 1.0,
                                        "trendStrength", 0.0, "trendLabel", "Start", "note", ""),
                                Map.of("round", 2, "demandIndex", 1.1, "costIndex", 1.05, "wageIndex", 1.02,
                                        "trendStrength", 0.3, "trendLabel", "Boom", "note", "peak"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environment.length()").value(2))
                .andExpect(jsonPath("$.environment[1].demandIndex").value(1.1))
                .andExpect(jsonPath("$.environment[1].trendLabel").value("Boom"));

        // A rule the facilitator can fix comes back as a code.
        mvc.perform(put("/api/templates/" + key + "/settings")
                        .header("Authorization", "Bearer " + acmeFacilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("rounds", 99))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("template.settings_invalid"))
                .andExpect(jsonPath("$.params.field").value("rounds"));

        mvc.perform(put("/api/templates/" + key + "/environment")
                        .header("Authorization", "Bearer " + acmeFacilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("rounds", List.of(
                                Map.of("round", 2, "demandIndex", 1.0, "costIndex", 1.0, "wageIndex", 1.0))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("template.environment_invalid"));

        // The shipped template is read-only, whoever asks.
        mvc.perform(put("/api/templates/standard-v1/settings")
                        .header("Authorization", "Bearer " + acmeFacilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("rounds", 4))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("template.read_only"));

        // Another tenant cannot even see it.
        mvc.perform(get("/api/templates/" + key)
                        .header("Authorization", "Bearer " + otherFacilitator))
                .andExpect(status().isNotFound());
    }

    @Test
    void the_catalogue_is_editable_until_the_template_has_a_session() throws Exception {
        String key = copyStandard();

        mvc.perform(put("/api/templates/" + key + "/products")
                        .header("Authorization", "Bearer " + acmeFacilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("products", List.of(
                                product("P1", "Basic", 1_000_000, 590_000, 50, 1000, 40, 1.0, -0.5),
                                product("P2", "Standard", 2_500_000, 1_430_000, 60, 600, 20, 1.0, 0.3))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(2))
                .andExpect(jsonPath("$.products[0].code").value("P1"))
                .andExpect(jsonPath("$.products[0].basePrice").value(1_000_000));

        mvc.perform(put("/api/templates/" + key + "/channels")
                        .header("Authorization", "Bearer " + acmeFacilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("channels", List.of(
                                Map.of("code", "DIGITAL", "name", "Digital", "reachWeight", 1.0,
                                        "brandAlpha", 1.6))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels.length()").value(1));

        mvc.perform(put("/api/templates/" + key + "/projects")
                        .header("Authorization", "Bearer " + acmeFacilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("projects", List.of(project("P1"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects.length()").value(1))
                .andExpect(jsonPath("$.projects[0].targetProduct").value("P1"));

        // The rules the engine depends on are refused here, not at round close in a classroom.
        mvc.perform(put("/api/templates/" + key + "/products")
                        .header("Authorization", "Bearer " + acmeFacilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("products", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("template.catalog_invalid"))
                .andExpect(jsonPath("$.params.field").value("products"));

        mvc.perform(put("/api/templates/" + key + "/projects")
                        .header("Authorization", "Bearer " + acmeFacilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("projects", List.of(project("P9"))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("template.catalog_invalid"))
                .andExpect(jsonPath("$.params.field").value("targetProduct"));

        // Once a session exists, the catalogue is frozen: its companies and decisions name these codes.
        mvc.perform(post("/api/runs")
                        .header("Authorization", "Bearer " + acmeFacilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Live cohort", "scenarioKey", key,
                                "teams", List.of(Map.of("name", "A", "companyName", "A Inc"))))))
                .andExpect(status().isCreated());

        mvc.perform(put("/api/templates/" + key + "/products")
                        .header("Authorization", "Bearer " + acmeFacilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("products", List.of(
                                product("P1", "Basic", 1_000_000, 590_000, 50, 1000, 40, 1.0, -0.5))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("template.in_use"));
    }

    private static Map<String, Object> product(String code, String name, long price, long cost,
                                               int quality, int demand, int perWorker,
                                               double incomeElasticity, double trendAffinity) {
        return Map.of("code", code, "name", name, "basePrice", price, "baseUnitCost", cost,
                "baseQuality", quality, "baseDemand", demand, "unitsPerWorker", perWorker,
                "incomeElasticity", incomeElasticity, "trendAffinity", trendAffinity);
    }

    private static Map<String, Object> project(String targetProduct) {
        // Map.of caps at ten pairs; this row carries twelve.
        Map<String, Object> project = new java.util.LinkedHashMap<>();
        project.put("code", "RND-1");
        project.put("name", "Process efficiency");
        project.put("targetProduct", targetProduct);
        project.put("advantageType", "UNIT_COST_DOWN");
        project.put("advantageValue", 0.15);
        project.put("hiddenCost", 300_000_000);
        project.put("hintBand", 0.2);
        project.put("durationMonths", 3);
        project.put("partialBenefit", true);
        project.put("benefitCurve", "LINEAR");
        project.put("benefitExponent", 1.0);
        project.put("overfunding", "LOST");
        return project;
    }

    private String copyStandard() throws Exception {
        MvcResult copied = mvc.perform(post("/api/templates/standard-v1/copy")
                        .header("Authorization", "Bearer " + acmeFacilitator))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(copied.getResponse().getContentAsString()).get("key").asText();
    }

    // ------------------------------------------------------------------ helpers

    private long tenant(String code, String name) {
        return jdbc.queryForObject(
                "insert into tenant (code, name) values (?, ?) returning id", Long.class, code, name);
    }

    private void user(long tenantId, String email, String role) {
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?)
                """, tenantId, email, encoder.encode("pw-" + email), email, role);
    }

    private int count(String table, String scenarioKey) {
        return jdbc.queryForObject("select count(*) from " + table + " where scenario_key = ?",
                Integer.class, scenarioKey);
    }

    private String login(String tenantCode, String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", tenantCode, "email", email,
                                "password", "pw-" + email))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }
}
