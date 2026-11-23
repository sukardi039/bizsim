package com.bizsim.sim;

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
 * D125: the market review is <em>charged per round, and locked by reading it</em>, not by submitting it.
 *
 * <p>The owner's rule: a ticked box may be unticked and the plan re-submitted — until the review has been
 * read. Reading is what makes it final, and the charge follows the purchase status of the <strong>final</strong>
 * submission of the round (the last plan submitted, or the auto-computed plan on timeout).
 *
 * <p>Two behaviours are pinned here, and they are different questions:
 *
 * <ol>
 *   <li><strong>Nothing is locked before a read.</strong> Submit with the tick, submit without it: the round
 *       ends up unbilled. D124 locked this at submission and charged for a report the trainee never opened.</li>
 *   <li><strong>A read is irreversible within the round.</strong> Read it, then submit without the tick: the
 *       stored plan is rewritten to keep the charge, so the report cannot be kept and its cost withdrawn.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportPurchaseTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private long tenant;
    private long runId;
    private long teamId;
    private String trainee;
    private String trainer;

    @BeforeEach
    void seed() throws Exception {
        for (String table : List.of("report_purchase", "round_statement", "round_result", "decision",
                "round", "player", "company_product", "company_staff", "company_rnd", "team", "run",
                "simulation")) {
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
        user("ada@acme.test", "PLAYER");
        trainer = login("trainer@acme.test");
        trainee = login("ada@acme.test");

        MvcResult created = mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(jdbc.queryForObject(
                                "select id from app_user where email = 'trainer@acme.test'", Long.class)))
                        .header("Authorization", "Bearer " + login("builder@acme.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Report class", "scenarioKey", "standard-v1",
                                "teams", List.of(Map.of("name", "A", "companyName", "A Ltd"))))))
                .andExpect(status().isCreated())
                .andReturn();
        runId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                        .header("Authorization", "Bearer " + trainer))
                .andExpect(status().isOk());
        teamId = jdbc.queryForObject("select id from team where run_id = ?", Long.class, runId);
        jdbc.update("insert into player (team_id, user_id, role_key, run_id) values (?, ?, 'OWNER', ?)",
                teamId, jdbc.queryForObject("select id from app_user where email = 'ada@acme.test'",
                        Long.class), runId);
    }

    /** The question the owner asked: bought, not read, before submission — can it be taken back? Yes. */
    @Test
    void a_ticked_box_can_be_unticked_before_the_review_is_read() throws Exception {
        submit(true);
        context().andExpect(jsonPath("$.reportRead").value(false))
                .andExpect(jsonPath("$.decision.marketReport").value(true));

        // Change of mind, and no report was ever opened.
        submit(false);

        context().andExpect(jsonPath("$.reportRead").value(false))
                .andExpect(jsonPath("$.decision.marketReport").value(false));
        assertThat(billed()).as("a report nobody read is not charged").isFalse();
    }

    /** Once read, the report cannot be kept while its cost is withdrawn. */
    @Test
    void reading_the_review_locks_the_purchase_for_the_round() throws Exception {
        submit(true);
        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamId + "/market-report")
                        .header("Authorization", "Bearer " + trainee))
                .andExpect(status().isOk());

        context().andExpect(jsonPath("$.reportRead").value(true));

        // The trainee edits the plan and submits with the review cleared — the hole this closes.
        submit(false);

        context().andExpect(jsonPath("$.reportRead").value(true))
                .andExpect(jsonPath("$.decision.marketReport").value(true));
        assertThat(billed()).as("re-submitting cannot withdraw the cost of a report already read").isTrue();
    }

    /** Reading is only possible once the review has been bought by a submitted plan. */
    @Test
    void the_review_cannot_be_read_before_it_is_bought() throws Exception {
        submit(false);
        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamId + "/market-report")
                        .header("Authorization", "Bearer " + trainee))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("report.not_purchased"));
        context().andExpect(jsonPath("$.reportRead").value(false));
    }

    private org.springframework.test.web.servlet.ResultActions context() throws Exception {
        return mvc.perform(get("/api/runs/" + runId + "/teams/" + teamId + "/context")
                        .header("Authorization", "Bearer " + trainee))
                .andExpect(status().isOk());
    }

    /** Will the open round's settlement bill the review, as things stand? */
    private boolean billed() {
        Boolean ticked = jdbc.queryForObject("""
                select coalesce((d.payload->>'marketReport')::boolean, false)
                from decision d join round r on r.id = d.round_id
                where r.run_id = ? and d.team_id = ?
                """, Boolean.class, runId, teamId);
        return Boolean.TRUE.equals(ticked);
    }

    private void submit(boolean withReview) throws Exception {
        // The shipped template carries three products and a decision must cover all of them.
        List<Map<String, Object>> production = new ArrayList<>();
        List<Map<String, Object>> price = new ArrayList<>();
        for (String code : List.of("P1", "P2", "P3")) {
            production.add(Map.of("product", code, "units", 100));
            price.add(Map.of("product", code, "unitPrice", 1000000));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("production", production);
        body.put("price", price);
        body.put("marketing", List.of());
        body.put("manpower", Map.of("production", 60));
        body.put("rnd", List.of());
        body.put("quality", List.of());
        body.put("marketReport", withReview);
        mvc.perform(post("/api/runs/" + runId + "/teams/" + teamId + "/decisions")
                        .header("Authorization", "Bearer " + trainee)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
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
