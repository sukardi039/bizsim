package com.bizsim.sim;

import com.bizsim.init.InitDataService;
import com.bizsim.support.PostgresTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D76: a trainee's development report. It must be their own company, in the language the class plays
 * in, built from the statements the console already reads — and it must not be a way to read another
 * company's numbers.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvaluationReportApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private InitDataService initData;
    @Autowired
    private EvaluationReportService evaluations;

    private long tenantId;
    private long runId;
    private List<Long> teams;

    /**
     * A played class leaves seats, parts and packages behind, and the seat rows reference users — so a
     * test that creates one must remove it or the next class fails on {@code delete from app_user}.
     * The same cleanup runs before and after.
     */
    private void clean() {
        for (String table : List.of("round_statement", "round_result", "decision", "round", "player",
                "company_product", "company_staff", "company_rnd", "team", "run", "simulation")) {
            jdbc.update("delete from " + table);
        }
        // scenario.frozen_for_package points at the package, so the reference goes first.
        jdbc.update("update scenario set frozen_for_package = null");
        for (String table : List.of("simulation_package_seat", "simulation_package_part",
                "simulation_package")) {
            jdbc.update("delete from " + table);
        }
        jdbc.update("delete from scenario where key like 'pkg-%' or tenant_id is not null");
        jdbc.update("delete from company_profile");
        jdbc.update("delete from app_user");
        jdbc.update("delete from tenant");
    }

    @AfterEach
    void tidy() {
        clean();
    }

    @BeforeEach
    void seed() {
        clean();
        jdbc.update("""
                insert into company_profile (tenant_id, name, company_name, products, notes)
                values (null, 'Standard manufacturer', '', '[]'::jsonb, 'shipped')
                """);

        InitDataService.Summary summary = initData.init("pilot",
                new InitDataService.Params(3, 3, 2, null, "zh", "pilot-pass-1", null, null, null));
        tenantId = summary.tenantId();
        runId = summary.runId();
        teams = jdbc.queryForList("select id from team where run_id = ? order by id", Long.class, runId);
        assertThat(teams).hasSize(3);
    }

    @Test
    void a_trainee_gets_their_own_report_in_the_class_language() throws Exception {
        long own = teamOf("player1@pilot.test");
        String token = login("player1@pilot.test");

        MvcResult result = mvc.perform(get("/api/runs/" + runId + "/teams/" + own + "/evaluation")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(new String(body, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1))
                .as("a PDF, not an error page").isEqualTo("%PDF-");
        assertThat(body.length).as("a real document, not a stub").isGreaterThan(3_000);
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("development-report");
    }

    @Test
    void the_report_speaks_the_language_the_package_pinned() {
        long own = teams.get(0);
        EvaluationReportService.Evaluation evaluation = evaluations.evaluate(tenantId, runId, own);
        // The class was seeded in Chinese, and run.lang carries it, so the server needs no hint from
        // the caller to pick the language (D66).
        assertThat(evaluation.term().lang()).isEqualTo("zh");
        assertThat(EvaluationReportService.Labels.of(evaluation.term().lang()).t("title"))
                .isEqualTo("经营发展报告");
    }

    @Test
    void a_trainee_cannot_ask_for_another_company() throws Exception {
        long own = teamOf("player1@pilot.test");
        long other = teams.stream().filter(id -> id != own).findFirst().orElseThrow();
        String token = login("player1@pilot.test");

        mvc.perform(get("/api/runs/" + runId + "/teams/" + other + "/evaluation")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("team.not_yours"));
    }

    @Test
    void staff_may_ask_for_any_company() throws Exception {
        String facilitator = login("trainer@pilot.test");
        for (long team : teams) {
            mvc.perform(get("/api/runs/" + runId + "/teams/" + team + "/evaluation")
                            .header("Authorization", "Bearer " + facilitator))
                    .andExpect(status().isOk());
        }
        // A company that is not in this session is a 404, not an empty report.
        mvc.perform(get("/api/runs/" + runId + "/teams/" + (teams.get(2) + 999) + "/evaluation")
                        .header("Authorization", "Bearer " + facilitator))
                .andExpect(status().isNotFound());
    }

    @Test
    void the_assessment_is_built_from_the_statements_the_console_reads() {
        long own = teams.get(0);
        EvaluationReportService.Evaluation evaluation = evaluations.evaluate(tenantId, runId, own);

        BigDecimal lastCash = jdbc.queryForObject("""
                select s.cash_after from round_statement s join round r on r.id = s.round_id
                where r.run_id = ? and s.team_id = ? order by r.number desc limit 1
                """, BigDecimal.class, runId, own);
        BigDecimal profit = jdbc.queryForObject("""
                select sum(s.profit) from round_statement s join round r on r.id = s.round_id
                where r.run_id = ? and s.team_id = ?
                """, BigDecimal.class, runId, own);

        assertThat(evaluation.totals().finalCash()).isEqualByComparingTo(lastCash);
        assertThat(evaluation.totals().profitTotal()).isEqualByComparingTo(profit);
        assertThat(evaluation.term().rounds()).hasSize(2);

        // Every dimension answers, and the liquidity band agrees with the direction cash moved.
        assertThat(evaluation.measures()).hasSize(6);
        assertThat(evaluation.measures()).allSatisfy(m -> {
            assertThat(m.band()).isNotNull();
            assertThat(m.values()).isNotEmpty();
        });
        EvaluationReportService.Measure liquidity = evaluation.measures().get(0);
        assertThat(liquidity.key()).isEqualTo("liquidity");
        if (evaluation.totals().cashDelta().signum() > 0
                && evaluation.totals().bufferMonths().compareTo(new BigDecimal("3")) >= 0) {
            assertThat(liquidity.band()).isEqualTo(EvaluationReportService.Band.STRONG);
        }
        if (evaluation.totals().cashDelta().signum() < 0
                && evaluation.totals().bufferMonths().compareTo(new BigDecimal("2")) < 0) {
            assertThat(liquidity.band()).isEqualTo(EvaluationReportService.Band.WATCH);
        }
    }

    @Test
    void a_session_with_nothing_computed_is_refused() throws Exception {
        InitDataService.Summary fresh = initData.init("later",
                new InitDataService.Params(2, 2, 0, null, "en", "later-pass-1", null, null, null));
        String token = loginOn("later", "trainer@later.test", "later-pass-1");
        long team = jdbc.queryForObject("select id from team where run_id = ? order by id limit 1",
                Long.class, fresh.runId());

        mvc.perform(get("/api/runs/" + fresh.runId() + "/teams/" + team + "/evaluation")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("report.no_rounds"));
    }

    // ------------------------------------------------------------------ helpers

    private long teamOf(String email) {
        return jdbc.queryForObject("""
                select p.team_id from player p join app_user u on u.id = p.user_id
                join team t on t.id = p.team_id
                where u.email = ? and t.run_id = ?
                """, Long.class, email, runId);
    }

    private String login(String email) throws Exception {
        return loginOn("pilot", email, "pilot-pass-1");
    }

    private String loginOn(String tenantCode, String email, String pass) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", tenantCode, "email", email, "password", pass))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }
}
