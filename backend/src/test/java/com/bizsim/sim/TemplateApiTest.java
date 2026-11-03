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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        // Only tenant-owned templates and the fixtures of this class; the shipped template stays.
        jdbc.update("delete from scenario where tenant_id is not null");
        jdbc.update("delete from app_user");
        jdbc.update("delete from tenant");

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
