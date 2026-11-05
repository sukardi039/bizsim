package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D48/D49/D50 end to end: an auto worker seated in a session decides, submits through the same path
 * as a trainee, is traceable, and replays identically.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AutoWorkerRoundApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private BotRoundService botRound;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private String adminToken;
    private String facilitatorToken;
    private String builderToken;
    private long tenantId;
    private long runId;
    private long botTeamId;
    private long botUserId;

    @BeforeEach
    void seed() throws Exception {
        for (String table : List.of("bot_decision", "facilitator_action", "round_snapshot",
                "round_statement", "round_result", "decision", "round", "player", "company_product",
                "company_staff", "company_rnd", "team", "run", "simulation", "simulation_package_seat",
                "company_profile")) {
            jdbc.update("delete from " + table);
        }
        // scenario <-> package is a two-way reference: break it, then remove both sides.
        jdbc.update("update scenario set frozen_for_package = null where frozen_for_package is not null");
        jdbc.update("delete from simulation_package");
        jdbc.update("delete from scenario where key like 'pkg-%'");
        jdbc.update("update app_user set bot_profile_id = null");
        jdbc.update("delete from bot_profile");
        jdbc.update("delete from app_user");
        jdbc.update("delete from tenant");

        tenantId = jdbc.queryForObject(
                "insert into tenant (code, name) values ('acme', 'Acme') returning id", Long.class);
        user("admin@acme.test", "ADMIN");
        user("builder@acme.test", "BUILDER");
        user("trainer@acme.test", "FACILITATOR");
        adminToken = login("admin@acme.test");
        builderToken = login("builder@acme.test");
        facilitatorToken = login("trainer@acme.test");

        setAutoWorkers(true);

        MvcResult created = mvc.perform(post("/api/bots")
                        .header("Authorization", "Bearer " + builderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Market 1", "level", "high"))))
                .andExpect(status().isCreated())
                .andReturn();
        botUserId = json.readTree(created.getResponse().getContentAsString()).get("userId").asLong();

        MvcResult run = mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(idOf("trainer@acme.test")))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Autumn",
                                "scenarioKey", "standard-v1",
                                "teams", List.of(
                                        Map.of("name", "alpha", "companyName", "Alpha"),
                                        Map.of("name", "market", "companyName", "Market 1_high"))))))
                .andExpect(status().isCreated())
                .andReturn();
        runId = json.readTree(run.getResponse().getContentAsString()).get("id").asLong();
        long teamOne = jdbc.queryForObject(
                "select id from team where run_id = ? order by id limit 1", Long.class, runId);
        botTeamId = jdbc.queryForObject(
                "select id from team where run_id = ? order by id desc limit 1", Long.class, runId);

        seat(teamOne, idOf("trainer@acme.test"));
        seat(botTeamId, botUserId);
    }

    @AfterEach
    void restoreTemplate() {
        setAutoWorkers(false);
        setGuidedRounds(0);
    }

    @Test
    void the_company_plays_itself_and_leaves_a_trace_that_replays() throws Exception {
        openRound();

        mvc.perform(post("/api/runs/" + runId + "/bots/decide")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decided").value(1));

        // Same path as a trainee: the submission is in the ordinary submissions view.
        mvc.perform(get("/api/runs/" + runId + "/decisions")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies[1].submitted").value(true));

        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());

        // It traded: a statement exists and the company moved.
        assertThat(jdbc.queryForObject(
                "select count(*) from round_statement where team_id = ?", Integer.class, botTeamId))
                .isEqualTo(1);

        mvc.perform(get("/api/runs/" + runId + "/bots")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].profileKey").value("market-1-high"))
                .andExpect(jsonPath("$[0].mode").value("MANUAL"))
                .andExpect(jsonPath("$[0].rationale").isNotEmpty());

        mvc.perform(post("/api/runs/" + runId + "/bots/" + botTeamId + "/replay")
                        .param("round", "1")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identical").value(true));
    }

    @Test
    void a_trainee_cannot_read_the_opponents_reasoning() throws Exception {
        openRound();
        mvc.perform(get("/api/runs/" + runId + "/bots")
                        .header("Authorization", "Bearer " + login("trainer@acme.test")))
                .andExpect(status().isOk());
        // The bot has no credentials at all, so it cannot even ask.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("tenantCode", "acme",
                                "email", "market-1-high@bots.invalid", "password", "x"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void the_opening_rounds_come_with_a_suggested_plan_that_is_never_submitted() throws Exception {
        setGuidedRounds(2);
        long playerTeam = jdbc.queryForObject(
                "select id from team where run_id = ? order by id limit 1", Long.class, runId);
        long player = otherPlayer("guided@acme.test");
        // A seat of their own: the trainer is seated on team one in the seed, so use a fresh company.
        seat(playerTeam, player);
        String token = login("guided@acme.test");
        openRound();

        MvcResult suggested = mvc.perform(get("/api/runs/" + runId + "/teams/" + playerTeam + "/suggestion")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guided").value(true))
                .andExpect(jsonPath("$.payload.production").isArray())
                .andExpect(jsonPath("$.payload.production.length()").value(3))
                .andReturn();

        // A draft, not a decision: the round still waits for the trainee.
        assertThat(jdbc.queryForObject("select count(*) from decision", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from bot_decision", Integer.class)).isZero();

        // Submitting the suggestion is a normal submission — the plan is valid as it stands.
        String body = json.readTree(suggested.getResponse().getContentAsString())
                .get("payload").toString();
        mvc.perform(post("/api/runs/" + runId + "/teams/" + playerTeam + "/decisions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Once they have submitted, there is nothing to guide.
        mvc.perform(get("/api/runs/" + runId + "/teams/" + playerTeam + "/suggestion")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guided").value(false));
    }

    @Test
    void the_guide_stops_after_the_configured_rounds_and_is_not_for_other_companies() throws Exception {
        setGuidedRounds(1);
        long playerTeam = jdbc.queryForObject(
                "select id from team where run_id = ? order by id limit 1", Long.class, runId);
        long player = otherPlayer("guided2@acme.test");
        seat(playerTeam, player);
        String token = login("guided2@acme.test");

        // Someone else's company is refused outright, not silently guided.
        mvc.perform(get("/api/runs/" + runId + "/teams/" + botTeamId + "/suggestion")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        openRound();
        mvc.perform(get("/api/runs/" + runId + "/teams/" + playerTeam + "/suggestion")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.guided").value(true));
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());

        // Round 2 is past the one guided round.
        mvc.perform(get("/api/runs/" + runId + "/teams/" + playerTeam + "/suggestion")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.guided").value(false));
    }

    @Test
    void a_broke_opponent_plays_a_smaller_month_instead_of_breaking_the_compute() throws Exception {
        // D59: a policy that plans beyond its cash used to poison the whole compute with an opaque
        // 500 (UnexpectedRollbackException) and leave the month unplayed.
        jdbc.update("update team set cash = 1000 where id = ?", botTeamId);
        openRound();
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                select count(*) from decision d join round r on r.id = d.round_id
                where r.run_id = ? and d.team_id = ?
                """, Integer.class, runId, botTeamId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from round_statement where team_id = ?",
                Integer.class, botTeamId)).isEqualTo(1);
    }

    @Test
    void an_opponent_that_cannot_pay_at_all_does_not_fail_the_compute() throws Exception {
        // Below zero, even a plan with no spending is unaffordable: the month is skipped, but the
        // compute must still land — one company's refusal is not the class's problem.
        jdbc.update("update team set cash = -5000000 where id = ?", botTeamId);
        openRound();
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from round_statement where team_id = ?",
                Integer.class, botTeamId)).isEqualTo(1);
    }

    @Test
    void a_reset_puts_the_session_back_to_month_zero() throws Exception {
        long teamOne = jdbc.queryForObject(
                "select id from team where run_id = ? order by id limit 1", Long.class, runId);
        java.math.BigDecimal opening = jdbc.queryForObject(
                "select cash from team where id = ?", java.math.BigDecimal.class, teamOne);
        openRound();
        mvc.perform(post("/api/runs/" + runId + "/bots/decide")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select current_round from run where id = ?", Integer.class, runId))
                .isEqualTo(1);

        mvc.perform(post("/api/runs/" + runId + "/reset")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roundsRemoved").value(1))
                .andExpect(jsonPath("$.companiesRestored").value(2));

        assertThat(jdbc.queryForObject("select current_round from run where id = ?", Integer.class, runId))
                .isEqualTo(0);
        assertThat(jdbc.queryForObject("select count(*) from round where run_id = ?", Integer.class, runId))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from decision", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from bot_decision", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from round_statement", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select cash from team where id = ?",
                java.math.BigDecimal.class, teamOne)).isEqualByComparingTo(opening);
        assertThat(jdbc.queryForList("select action from facilitator_action where action = 'RUN_RESET'",
                String.class)).hasSize(1);
    }

    @Test
    void computing_early_lets_the_opponent_play_instead_of_skipping_the_month() throws Exception {
        openRound();
        // Straight to compute, before the seeded moment: this is what a facilitator does in a workshop.
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("""
                select count(*) from decision d join round r on r.id = d.round_id
                where r.run_id = ? and d.team_id = ?
                """, Integer.class, runId, botTeamId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select mode from bot_decision where team_id = ?",
                String.class, botTeamId)).isEqualTo("AT_CLOSE");
        assertThat(jdbc.queryForObject("select count(*) from round_statement where team_id = ?",
                Integer.class, botTeamId)).isEqualTo(1);
    }

    @Test
    void a_package_can_seat_an_opponent_beside_a_person() throws Exception {
        long alpha = profile("Alpha");
        long market = profile("Market 1_high");

        MvcResult published = mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + builderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "With an opponent", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(alpha, market),
                                "traineeIds", java.util.Arrays.asList(null, botUserId)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seats.length()").value(1))
                .andExpect(jsonPath("$.seats[0].position").value(1))
                .andReturn();
        long packageId = json.readTree(published.getResponse().getContentAsString()).get("id").asLong();

        // The session started from that package seats the opponent like anyone else. A facilitator
        // may hold only one session in progress, and the seed already has one.
        user("trainer3@acme.test", "FACILITATOR");
        String thirdFacilitator = login("trainer3@acme.test");
        MvcResult session = mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + thirdFacilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Autumn")))
                ).andExpect(status().isCreated()).andReturn();
        long newRun = json.readTree(session.getResponse().getContentAsString()).get("id").asLong();

        assertThat(jdbc.queryForObject(
                "select count(*) from player where run_id = ? and bot_profile_id is not null",
                Integer.class, newRun)).isEqualTo(1);
    }

    @Test
    void an_opponent_cannot_be_seated_by_a_template_that_does_not_offer_them() throws Exception {
        setAutoWorkers(false);
        int before = jdbc.queryForObject("select count(*) from scenario", Integer.class);
        long alpha = profile("Alpha");
        long market = profile("Market 1_high");

        mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + builderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "No opponents", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(alpha, market),
                                "traineeIds", java.util.Arrays.asList(null, botUserId)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("bot.switch_off"));

        assertThat(jdbc.queryForObject("select count(*) from scenario", Integer.class)).isEqualTo(before);
    }

    @Test
    void the_worker_submits_by_itself_without_the_facilitator() throws Exception {
        openRound();
        // Move the clock as if the seeded moment inside the round had passed: the sweep, not a button,
        // must be what makes the opponent play.
        jdbc.update("update round set opened_at = now() - interval '3 hours' where run_id = ?", runId);

        botRound.tick();

        Integer submitted = jdbc.queryForObject("""
                select count(*) from decision d join round r on r.id = d.round_id
                where r.run_id = ? and d.team_id = ?
                """, Integer.class, runId, botTeamId);
        assertThat(submitted).isEqualTo(1);
        assertThat(jdbc.queryForObject("select mode from bot_decision where team_id = ?",
                String.class, botTeamId)).isEqualTo("SCHEDULED");
    }

    @Test
    void a_trainee_sees_the_class_ranked_by_profit_and_nothing_more() throws Exception {
        long playerTeam = jdbc.queryForObject(
                "select id from team where run_id = ? order by id limit 1", Long.class, runId);
        seat(playerTeam, otherPlayer("one@acme.test"));

        openRound();
        mvc.perform(post("/api/runs/" + runId + "/bots/decide")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
        // The person submits too, so the table has two real rows.
        mvc.perform(post("/api/runs/" + runId + "/teams/" + playerTeam + "/decisions")
                        .header("Authorization", "Bearer " + login("one@acme.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "production", List.of(
                                        Map.of("product", "P1", "units", 600),
                                        Map.of("product", "P2", "units", 150),
                                        Map.of("product", "P3", "units", 30)),
                                "price", List.of(
                                        Map.of("product", "P1", "unitPrice", 1_000_000),
                                        Map.of("product", "P2", "unitPrice", 2_500_000),
                                        Map.of("product", "P3", "unitPrice", 5_000_000)),
                                "marketing", List.of(Map.of("channel", "DIGITAL", "spend", 1_000_000)),
                                "manpower", Map.of("production", 30),
                                "capacityPurchase", 0,
                                "marketReport", false))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());

        // D52: hidden until the facilitator shows it.
        mvc.perform(get("/api/runs/" + runId + "/standings")
                        .header("Authorization", "Bearer " + login("one@acme.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(post("/api/runs/" + runId + "/standings")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("visible", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visible").value(true));

        MvcResult result = mvc.perform(get("/api/runs/" + runId + "/standings")
                        .header("Authorization", "Bearer " + login("one@acme.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[0].company").isString())
                .andExpect(jsonPath("$[0].profit").isNumber())
                .andReturn();

        // D21 stays true for everything else: no share, no cash, no statement in the payload.
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("marketShare").doesNotContain("cash")
                .doesNotContain("stock").doesNotContain("share");
        assertThat(body).contains("\"own\":true");

        // Hiding it again closes the class view but never the facilitator's.
        mvc.perform(post("/api/runs/" + runId + "/standings")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("visible", false))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/runs/" + runId + "/standings")
                        .header("Authorization", "Bearer " + login("one@acme.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // D52: both moments are in the audit trail, so a class can ask when the ranking appeared.
        assertThat(jdbc.queryForList("select action from facilitator_action where run_id = ? "
                + "and action like 'STANDINGS%' order by id", String.class, runId))
                .containsExactly("STANDINGS_SHOW", "STANDINGS_HIDE");

        // The facilitator reads the same table: it is a view of the round, not a trainee privilege.
        mvc.perform(get("/api/runs/" + runId + "/standings")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // A trainee who is not seated in this session sees no table at all.
        user("stranger@acme.test", "PLAYER");
        mvc.perform(get("/api/runs/" + runId + "/standings")
                        .header("Authorization", "Bearer " + login("stranger@acme.test")))
                .andExpect(status().isForbidden());
    }

    @Test
    void a_template_without_the_switch_cannot_acquire_an_opponent() throws Exception {
        setAutoWorkers(false);
        // One facilitator may hold only one session in progress, so this needs a second one.
        user("trainer2@acme.test", "FACILITATOR");
        String otherFacilitatorToken = login("trainer2@acme.test");
        MvcResult run = mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(idOf("trainer2@acme.test")))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "No bots",
                                "scenarioKey", "standard-v1",
                                "teams", List.of(
                                        Map.of("name", "solo", "companyName", "Solo"),
                                        Map.of("name", "spare", "companyName", "Spare"))))))
                .andExpect(status().isCreated())
                .andReturn();
        long otherRun = json.readTree(run.getResponse().getContentAsString()).get("id").asLong();
        long team = jdbc.queryForObject(
                "select id from team where run_id = ? order by id desc limit 1", Long.class, otherRun);

        mvc.perform(post("/api/runs/" + otherRun + "/players")
                        .header("Authorization", "Bearer " + otherFacilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "teamId", team, "userId", botUserId, "roleKey", "OWNER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("bot.switch_off"));
    }

    // ------------------------------------------------------------------ helpers

    private void setGuidedRounds(int rounds) {
        jdbc.update("update scenario set definition = jsonb_set(definition, '{guidedRounds}', ?::jsonb) "
                + "where key = 'standard-v1'", String.valueOf(rounds));
    }

    private long profile(String companyName) {
        return jdbc.queryForObject("""
                insert into company_profile (tenant_id, name, company_name, products)
                values (?, ?, ?, '[]'::jsonb) returning id
                """, Long.class, tenantId, companyName, companyName);
    }

    private void setAutoWorkers(boolean on) {
        jdbc.update("update scenario set definition = jsonb_set(definition, '{autoWorkers}', ?::jsonb) "
                + "where key = 'standard-v1'", String.valueOf(on));
    }

    private void seat(long teamId, long userId) throws Exception {
        mvc.perform(post("/api/runs/" + runId + "/players")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "teamId", teamId, "userId", userId, "roleKey", "OWNER"))))
                .andExpect(status().isCreated());
    }

    private void openRound() throws Exception {
        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
    }

    private void user(String email, String role) {
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?)
                """, tenantId, email, encoder.encode("pw-" + email), email, role);
    }

    private long otherPlayer(String email) {
        if (!tableHas(email)) {
            user(email, "PLAYER");
        }
        return idOf(email);
    }

    private boolean tableHas(String email) {
        Integer count = jdbc.queryForObject("select count(*) from app_user where email = ?",
                Integer.class, email);
        return count != null && count > 0;
    }

    private long idOf(String email) {
        return jdbc.queryForObject("select id from app_user where email = ?", Long.class, email);
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("tenantCode", "acme", "email", email,
                                "password", "pw-" + email))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
