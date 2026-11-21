package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D115 at the API: the debrief's class table is served from the round's own statements, with names in the
 * class's language, and it is staff-only — what a rival won in a class is competition data.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClassShareApiTest extends PostgresTestBase {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    @Test
    void the_debrief_reads_the_class_table_from_the_round_it_computed() throws Exception {
        long tenantId = jdbc.queryForObject(
                "insert into tenant (code, name) values ('acme-cls', 'Acme') returning id", Long.class);
        user(tenantId, "admin@cls.test", "ADMIN");
        user(tenantId, "trainer@cls.test", "FACILITATOR");
        user(tenantId, "one@cls.test", "PLAYER");
        user(tenantId, "two@cls.test", "PLAYER");
        String admin = login("acme-cls", "admin@cls.test");
        String trainer = login("acme-cls", "trainer@cls.test");
        String one = login("acme-cls", "one@cls.test");
        String two = login("acme-cls", "two@cls.test");

        long facilitatorId = jdbc.queryForObject(
                "select id from app_user where email = 'trainer@cls.test'", Long.class);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Cohort");
        body.put("scenarioKey", "standard-v1");
        body.put("teams", List.of(
                Map.of("name", "Alpha", "companyName", "Alpha Inc"),
                Map.of("name", "Beta", "companyName", "Beta Inc")));
        MvcResult created = mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(facilitatorId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode run = json.readTree(created.getResponse().getContentAsString());
        long runId = run.get("id").asLong();
        long alpha = run.get("teams").get(0).get("id").asLong();
        long beta = run.get("teams").get(1).get("id").asLong();
        seat(trainer, runId, alpha, "one@cls.test");
        seat(trainer, runId, beta, "two@cls.test");

        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                .header("Authorization", "Bearer " + trainer)).andExpect(status().isOk());
        submit(one, runId, alpha, "DIGITAL", 30_000_000L);
        submit(two, runId, beta, "FIELD", 30_000_000L);
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + trainer)).andExpect(status().isOk());

        MvcResult table = mvc.perform(get("/api/runs/" + runId + "/class-shares")
                        .header("Authorization", "Bearer " + trainer))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = json.readTree(
                table.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(page.get("weighted").asBoolean())
                .as("the shipped template weights reach by class (D111)").isTrue();
        JsonNode rows = page.get("rows");
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.size()).as("three products x three classes").isEqualTo(9);

        JsonNode p1Mass = null;
        for (JsonNode row : rows) {
            assertThat(row.get("market").asLong()).as("a class the class contested has a market").isPositive();
            assertThat(row.get("productName").asText()).isNotBlank();
            assertThat(row.get("segmentName").asText()).isNotBlank();
            assertThat(row.get("companies").size()).as("both companies carry every product").isEqualTo(2);
            if ("P1".equals(row.get("product").asText()) && "SEG-BASIC".equals(row.get("segment").asText())) {
                p1Mass = row;
            }
        }
        assertThat(p1Mass).isNotNull();
        // The awards in one class are that class's market: the table is the allocation, not a redrawing.
        long awarded = 0;
        for (JsonNode company : p1Mass.get("companies")) {
            awarded += company.get("demand").asLong();
            assertThat(company.get("company").asText()).isNotBlank();
        }
        assertThat(awarded).isEqualTo(p1Mass.get("market").asLong());

        // Competition data: a trainee asking the same question is refused, not shown a rival's cell.
        mvc.perform(get("/api/runs/" + runId + "/class-shares")
                        .header("Authorization", "Bearer " + one))
                .andExpect(status().isForbidden());
    }

    @Test
    void a_template_that_is_not_class_weighted_returns_no_table() throws Exception {
        // The shipped template IS weighted (D111); a tenant draft of it with the weights cleared is not.
        // The endpoint must then return nothing rather than a split of a market that was never split.
        Long tenantId = jdbc.queryForObject(
                "insert into tenant (code, name) values ('acme-plain', 'Plain') returning id", Long.class);
        String draft = "plain-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        scenarios().cloneInto("standard-v1", draft, "plain", tenantId, "DRAFT");
        List<ScenarioRepository.ChannelRow> plain = new java.util.ArrayList<>(scenarios().channels(draft));
        plain.replaceAll(row -> new ScenarioRepository.ChannelRow(row.code(), row.name(),
                row.reachWeight(), row.brandAlpha()));
        scenarios().saveChannels(draft, plain);
        assertThat(scenarios().load(draft).channels())
                .allMatch(channel -> !channel.segmentSpecific());

        user(tenantId, "admin@plain.test", "ADMIN");
        user(tenantId, "trainer@plain.test", "FACILITATOR");
        String admin = login("acme-plain", "admin@plain.test");
        String trainer = login("acme-plain", "trainer@plain.test");
        long facilitatorId = jdbc.queryForObject(
                "select id from app_user where email = 'trainer@plain.test'", Long.class);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Plain cohort");
        body.put("scenarioKey", draft);
        body.put("teams", List.of(Map.of("name", "Alpha", "companyName", "Alpha Inc")));
        MvcResult created = mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(facilitatorId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        long runId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                .header("Authorization", "Bearer " + trainer)).andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + trainer)).andExpect(status().isOk());

        MvcResult table = mvc.perform(get("/api/runs/" + runId + "/class-shares")
                        .header("Authorization", "Bearer " + trainer))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = json.readTree(
                table.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(page.get("weighted").asBoolean())
                .as("a class-blind template says so, rather than looking broken").isFalse();
        assertThat(page.get("rows").size()).isZero();
    }

    /**
     * A round computed before the breakdown existed has no class rows and never will: the allocation was
     * recorded per product only. The endpoint must still say the template *is* class-weighted, so the
     * debrief can explain the empty table instead of leaving a facilitator to guess (reported by
     * construction: every class played before 0.27.0 looks like this).
     */
    @Test
    void a_round_computed_before_the_breakdown_says_so_rather_than_looking_class_blind() throws Exception {
        long tenantId = jdbc.queryForObject(
                "insert into tenant (code, name) values ('acme-old', 'Old') returning id", Long.class);
        user(tenantId, "admin@old.test", "ADMIN");
        user(tenantId, "trainer@old.test", "FACILITATOR");
        String admin = login("acme-old", "admin@old.test");
        String trainer = login("acme-old", "trainer@old.test");
        long facilitatorId = jdbc.queryForObject(
                "select id from app_user where email = 'trainer@old.test'", Long.class);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Old cohort");
        body.put("scenarioKey", "standard-v1");
        body.put("teams", List.of(Map.of("name", "Alpha", "companyName", "Alpha Inc")));
        MvcResult created = mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(facilitatorId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        long runId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                .header("Authorization", "Bearer " + trainer)).andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + trainer)).andExpect(status().isOk());

        // Strip the breakdown from the stored detail: exactly the shape a pre-0.27.0 statement has.
        jdbc.update("update round_statement set detail = (detail - 'classes')::jsonb"
                + " where round_id in (select id from round where run_id = ?)", runId);

        MvcResult table = mvc.perform(get("/api/runs/" + runId + "/class-shares")
                        .header("Authorization", "Bearer " + trainer))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = json.readTree(
                table.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(page.get("weighted").asBoolean())
                .as("the template is class-weighted; the round simply predates the record").isTrue();
        assertThat(page.get("rows").size()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    @Autowired
    ScenarioRepository scenarioRepository;

    private ScenarioRepository scenarios() {
        return scenarioRepository;
    }

    private void seat(String trainer, long runId, long teamId, String email) throws Exception {
        Long userId = jdbc.queryForObject("select id from app_user where email = ?", Long.class, email);
        mvc.perform(post("/api/runs/" + runId + "/players")
                        .header("Authorization", "Bearer " + trainer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "teamId", teamId, "userId", userId, "roleKey", "OWNER"))))
                .andExpect(status().isCreated());
    }

    private void submit(String token, long runId, long teamId, String channel, long marketing)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("marketReport", false);
        body.put("production", List.of(
                Map.of("product", "P1", "units", 600),
                Map.of("product", "P2", "units", 150),
                Map.of("product", "P3", "units", 30)));
        body.put("price", List.of(
                Map.of("product", "P1", "unitPrice", 1_000_000),
                Map.of("product", "P2", "unitPrice", 2_500_000),
                Map.of("product", "P3", "unitPrice", 5_000_000)));
        body.put("marketing", List.of(Map.of("channel", channel, "spend", marketing)));
        body.put("manpower", Map.of("production", 40));
        body.put("rnd", List.of());
        body.put("capacityPurchase", 0);
        mvc.perform(post("/api/runs/" + runId + "/teams/" + teamId + "/decisions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
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
                                "tenantCode", tenantCode, "email", email,
                                "password", "pw-" + email))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
