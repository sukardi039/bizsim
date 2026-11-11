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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** S2a exit test: a real round goes in, a deterministic statement comes out. */
@SpringBootTest
@AutoConfigureMockMvc
class RoundFlowApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private long tenantId;
    private long runId;
    private long teamOne;
    private long teamTwo;
    private String facilitatorToken;
    private String playerOneToken;
    private String playerTwoToken;
    private String adminToken;

    @BeforeEach
    void seed() throws Exception {
        for (String table : List.of("round_statement", "round_result", "decision", "round", "player",
                "company_product", "company_staff", "company_rnd", "team", "run", "simulation",
                "app_user", "tenant")) {
            jdbc.update("delete from " + table);
        }

        long tenant = jdbc.queryForObject(
                "insert into tenant (code, name) values ('acme', 'Acme') returning id", Long.class);
        tenantId = tenant;
        user(tenant, "trainer@acme.test", "FACILITATOR");
        user(tenant, "one@acme.test", "PLAYER");
        user(tenant, "two@acme.test", "PLAYER");
        // POST /api/runs is admin-only now; an admin bootstraps the run for the facilitator (D24).
        user(tenant, "admin@acme.test", "ADMIN");
        facilitatorToken = login("trainer@acme.test");
        playerOneToken = login("one@acme.test");
        playerTwoToken = login("two@acme.test");
        adminToken = login("admin@acme.test");

        JsonNode run = createRun();
        runId = run.get("id").asLong();
        teamOne = run.get("teams").get(0).get("id").asLong();
        teamTwo = run.get("teams").get(1).get("id").asLong();

        addPlayer(teamOne, "one@acme.test");
        addPlayer(teamTwo, "two@acme.test");
        assertThat(playerTwoToken).isNotBlank();
    }

    @Test
    void a_submitted_month_is_computed_for_every_company() throws Exception {
        openRound();

        submit(playerOneToken, teamOne, payload(600, 150, 30, 20_000_000L, 30, 0))
                .andExpect(status().isOk());

        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teams.length()").value(2));

        List<Map<String, Object>> board = jdbc.queryForList("""
                select rr.team_id, (rr.score->>'profit')::numeric as profit from round_result rr
                join round r on r.id = rr.round_id where r.run_id = ? order by rr.team_id
                """, runId);
        assertThat(board).hasSize(2);

        // The company that submitted sells 600/150/30 at full capacity; the silent one sells nothing
        // and still pays payroll and fixed cost.
        assertThat(profit(board, teamOne)).isGreaterThan(profit(board, teamTwo));
        assertThat(profit(board, teamTwo)).isLessThan(java.math.BigDecimal.ZERO);

        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamOne + "/statements")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].round").value(1))
                .andExpect(jsonPath("$[0].revenue").isNumber());
    }

    @Test
    void the_statement_carries_the_cash_balance_the_round_opened_with() throws Exception {
        openRound();
        submit(playerOneToken, teamOne, payload(600, 150, 30, 20_000_000L, 30, 0))
                .andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());

        // Round 1 opens at the company's starting cash...
        Map<String, Object> first = jdbc.queryForMap("""
                select s.opening_cash, s.cash_after, s.profit,
                       (s.detail ->> 'cashChange')::numeric as cash_change
                from round_statement s
                join round r on r.id = s.round_id where r.run_id = ? and s.team_id = ? and r.number = 1
                """, runId, teamOne);
        // The backfill (V17) reads cashChange out of `detail`; the invariant it relies on is that
        // opening + movement = closing, exactly, with no rounding drift.
        assertThat(first.get("cash_change")).isNotNull();
        assertThat(((java.math.BigDecimal) first.get("opening_cash"))
                .add((java.math.BigDecimal) first.get("cash_change")))
                .isEqualByComparingTo((java.math.BigDecimal) first.get("cash_after"));
        java.math.BigDecimal opening = (java.math.BigDecimal) first.get("opening_cash");
        assertThat(opening).isEqualByComparingTo(new java.math.BigDecimal("2000000000"));
        // ...and closing - opening is the cash the round actually moved.
        java.math.BigDecimal closing = (java.math.BigDecimal) first.get("cash_after");
        java.math.BigDecimal changed = closing.subtract(opening);
        assertThat(changed).isEqualByComparingTo(
                (java.math.BigDecimal) first.get("profit"));

        // Round 2 opens exactly where round 1 closed: the continuity the debrief shows.
        openRound();
        submit(playerOneToken, teamOne, payload(600, 150, 30, 20_000_000L, 30, 0))
                .andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());

        Map<String, Object> second = jdbc.queryForMap("""
                select s.opening_cash from round_statement s
                join round r on r.id = s.round_id where r.run_id = ? and s.team_id = ? and r.number = 2
                """, runId, teamOne);
        assertThat((java.math.BigDecimal) second.get("opening_cash")).isEqualByComparingTo(closing);

        // V17 exists to fix rows written before the column did. Run the migration's own SQL against
        // zeroed rows and require it to restore them exactly -- a backfill that ships without being
        // executed once is a hypothesis, not a fix.
        List<java.math.BigDecimal> before = jdbc.queryForList(
                "select opening_cash from round_statement s join round r on r.id = s.round_id "
                        + "where r.run_id = ? order by r.number, s.team_id",
                java.math.BigDecimal.class, runId);
        jdbc.update("update round_statement set opening_cash = 0 where round_id in "
                + "(select id from round where run_id = ?)", runId);
        for (String statement : backfillStatements()) {
            jdbc.execute(statement);
        }
        List<java.math.BigDecimal> after = jdbc.queryForList(
                "select opening_cash from round_statement s join round r on r.id = s.round_id "
                        + "where r.run_id = ? order by r.number, s.team_id",
                java.math.BigDecimal.class, runId);
        assertThat(after).hasSize(before.size());
        for (int i = 0; i < before.size(); i++) {
            assertThat(after.get(i)).isEqualByComparingTo(before.get(i));
        }
    }

    /** The executable part of V17: line comments dropped, statements split on ';'. */
    private static List<String> backfillStatements() throws Exception {
        String sql = new org.springframework.core.io.ClassPathResource(
                "db/migration/V17__backfill_opening_cash.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder bare = new StringBuilder();
        for (String line : sql.split("\\R")) {
            if (!line.trim().startsWith("--")) {
                bare.append(line).append('\n');
            }
        }
        List<String> statements = new java.util.ArrayList<>();
        for (String chunk : bare.toString().split(";")) {
            String trimmed = chunk.trim();
            String lower = trimmed.toLowerCase();
            if (lower.startsWith("update") || lower.startsWith("alter") || lower.startsWith("insert")) {
                statements.add(trimmed);
            }
        }
        return statements;
    }

    @Test
    void closing_twice_does_not_compute_the_month_twice() throws Exception {
        openRound();
        submit(playerOneToken, teamOne, payload(600, 150, 30, 20_000_000L, 30, 0))
                .andExpect(status().isOk());

        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isNoContent());

        Integer results = jdbc.queryForObject("""
                select count(*) from round_result rr join round r on r.id = rr.round_id
                where r.run_id = ? and r.number = 1
                """, Integer.class, runId);
        assertThat(results).isEqualTo(2);
    }

    @Test
    void a_plan_that_needs_more_workers_than_the_company_has_is_rejected() throws Exception {
        openRound();
        // 10,000 units of P1 at 40 units per worker needs 250 workers; the company starts with 30.
        submit(playerOneToken, teamOne, payload(10_000, 0, 0, 0L, 30, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("workers")));
    }

    @Test
    void spending_more_than_the_bank_balance_is_409_with_a_breakdown() throws Exception {
        openRound();
        submit(playerOneToken, teamOne, payload(600, 150, 30, 5_000_000_000L, 30, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("insufficient cash"))
                .andExpect(jsonPath("$.lines.marketing").value(5_000_000_000L));
    }

    @Test
    void a_player_cannot_submit_for_another_company() throws Exception {
        openRound();
        submit(playerOneToken, teamTwo, payload(600, 150, 30, 0L, 30, 0))
                .andExpect(status().isForbidden());
    }

    @Test
    void an_unknown_field_is_rejected_rather_than_ignored() throws Exception {
        openRound();
        Map<String, Object> body = new java.util.LinkedHashMap<>(
                payload(600, 150, 30, 0L, 30, 0));
        body.put("colour", "red");
        mvc.perform(post("/api/runs/" + runId + "/teams/" + teamOne + "/decisions")
                        .header("Authorization", "Bearer " + playerOneToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void a_submission_after_the_clock_deadline_is_409() throws Exception {
        openRound();
        jdbc.update("update round set closes_at = now() - interval '1 minute' where run_id = ?", runId);

        submit(playerOneToken, teamOne, payload(600, 150, 30, 0L, 30, 0))
                .andExpect(status().isConflict());
    }

    @Test
    void the_form_context_never_exposes_the_hidden_project_cost() throws Exception {
        openRound();
        MvcResult result = mvc.perform(get("/api/runs/" + runId + "/teams/" + teamOne + "/context")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rndProjects.length()").value(5))
                .andExpect(jsonPath("$.products.length()").value(3))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("hiddenCost");
        assertThat(body).contains("hintLow");
        assertThat(json.readTree(body).get("rndProjects").get(0).has("hintHigh")).isTrue();
    }

    @Test
    void a_facilitator_creates_a_trainee_account_and_seats_it() throws Exception {
        MvcResult created = mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", "newcomer@acme.test", "displayName", "Newcomer",
                                "password", "learner1234"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("PLAYER"))
                .andExpect(jsonPath("$.email").value("newcomer@acme.test"))
                .andReturn();
        long newcomerId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        // The account works with the credential the facilitator chose.
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", "acme", "email", "newcomer@acme.test",
                                "password", "learner1234"))))
                .andExpect(status().isOk())
                .andReturn();
        String newcomer = json.readTree(login.getResponse().getContentAsString()).get("token").asText();

        // Seated on a company, the trainee sees that company's form.
        mvc.perform(post("/api/runs/" + runId + "/players")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "teamId", teamOne, "userId", newcomerId, "roleKey", "OWNER"))))
                .andExpect(status().isCreated());
        openRound();
        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamOne + "/context")
                        .header("Authorization", "Bearer " + newcomer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(false));
    }

    @Test
    void a_facilitator_deletes_a_simulation_and_can_start_again() throws Exception {
        openRound();
        submit(playerOneToken, teamOne, payload(100, 0, 0, 0L, 30, 0)).andExpect(status().isOk());
        long simulationId = jdbc.queryForObject(
                "select simulation_id from run where id = ?", Long.class, runId);
        long roundId = jdbc.queryForObject(
                "select id from round where run_id = ?", Long.class, runId);

        mvc.perform(delete("/api/runs/" + runId)
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isNoContent());

        // Everything that belonged to the run is gone, and so is the empty simulation shell.
        for (String table : List.of("round", "team")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where run_id = ?",
                    Integer.class, runId)).as(table).isZero();
        }
        for (String table : List.of("decision", "round_result", "round_statement")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where round_id = ?",
                    Integer.class, roundId)).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("select count(*) from simulation where id = ?",
                Integer.class, simulationId)).isZero();
        // D22: accounts are people, sessions are disposable. The seats go; the accounts stay.
        assertThat(jdbc.queryForObject(
                "select count(*) from app_user where email in ('one@acme.test', 'two@acme.test')",
                Integer.class)).isEqualTo(2);
        assertThat(login("one@acme.test")).isNotBlank();
        mvc.perform(get("/api/runs/" + runId).header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isNotFound());

        // D21 counts unfinished runs: with the run gone the facilitator is free to start the next one.
        mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(idOf("trainer@acme.test")))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Clean slate", "scenarioKey", "standard-v1",
                                "teams", List.of(Map.of("name", "A", "companyName", "A Inc"))))))
                .andExpect(status().isCreated());
    }

    @Test
    void a_trainee_account_outlives_the_session_and_can_be_seated_again() throws Exception {
        long playerOneId = jdbc.queryForObject(
                "select id from app_user where email = 'one@acme.test'", Long.class);

        mvc.perform(delete("/api/runs/" + runId)
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isNoContent());

        // D22: the person is unchanged by the session ending. Their seat is gone, their account is not.
        assertThat(jdbc.queryForObject("select count(*) from player where user_id = ?",
                Integer.class, playerOneId)).isZero();
        String returning = login("one@acme.test");
        assertThat(returning).isNotBlank();

        // And they can be seated again in the next session — the whole point of a personal account.
        MvcResult created = mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(idOf("trainer@acme.test")))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Next cohort", "scenarioKey", "standard-v1",
                                "teams", List.of(Map.of("name", "A", "companyName", "A Inc"))))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode next = json.readTree(created.getResponse().getContentAsString());
        mvc.perform(post("/api/runs/" + next.get("id").asLong() + "/players")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "teamId", next.get("teams").get(0).get("id").asLong(),
                                "userId", playerOneId, "roleKey", "OWNER"))))
                .andExpect(status().isCreated());

        openRound(next.get("id").asLong());
        mvc.perform(get("/api/runs/" + next.get("id").asLong()
                        + "/teams/" + next.get("teams").get(0).get("id").asLong() + "/context")
                        .header("Authorization", "Bearer " + returning))
                .andExpect(status().isOk());
    }

    @Test
    void a_facilitator_removes_a_trainee_from_the_school() throws Exception {
        long playerOneId = jdbc.queryForObject(
                "select id from app_user where email = 'one@acme.test'", Long.class);
        long facilitatorId = jdbc.queryForObject(
                "select id from app_user where email = 'trainer@acme.test'", Long.class);

        // A trainee cannot remove or disable anyone.
        mvc.perform(delete("/api/users/" + playerOneId)
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/users/" + playerOneId + "/deactivate")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isForbidden());

        // Another facilitator is not a trainee: 404. Nobody deletes themselves: 400.
        user(tenantId, "trainer8@acme.test", "FACILITATOR");
        long otherFacilitator = jdbc.queryForObject(
                "select id from app_user where email = 'trainer8@acme.test'", Long.class);
        mvc.perform(delete("/api/users/" + otherFacilitator)
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/users/" + facilitatorId)
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isBadRequest());

        // A trainee who has played cannot be deleted: their decision references the account.
        openRound();
        submit(playerOneToken, teamOne, payload(100, 0, 0, 0L, 30, 0)).andExpect(status().isOk());
        mvc.perform(delete("/api/users/" + playerOneId)
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isConflict());

        // Deactivation is the tool for "this person left": login stops, history and seat stay.
        mvc.perform(post("/api/users/" + playerOneId + "/deactivate")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", "acme", "email", "one@acme.test",
                                "password", "pw-one@acme.test"))))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from player where user_id = ?",
                Integer.class, playerOneId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from app_user where id = ?",
                Integer.class, playerOneId)).isEqualTo(1);

        // And it is reversible.
        mvc.perform(post("/api/users/" + playerOneId + "/activate")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isNoContent());
        assertThat(login("one@acme.test")).isNotBlank();
    }

    @Test
    void deleting_a_simulation_is_owner_and_role_guarded() throws Exception {
        // A trainee cannot delete anything.
        mvc.perform(delete("/api/runs/" + runId)
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isForbidden());

        // Another facilitator of the same tenant cannot delete it, and must not learn it exists.
        user(tenantId, "trainer6@acme.test", "FACILITATOR");
        mvc.perform(delete("/api/runs/" + runId)
                        .header("Authorization", "Bearer " + loginOn("acme", "trainer6@acme.test")))
                .andExpect(status().isForbidden());

        // An admin runs the tenant and may.
        user(tenantId, "boss3@acme.test", "ADMIN");
        mvc.perform(delete("/api/runs/" + runId)
                        .header("Authorization", "Bearer " + loginOn("acme", "boss3@acme.test")))
                .andExpect(status().isNoContent());
    }

    @Test
    void account_creation_is_facilitator_only_and_guarded() throws Exception {
        Map<String, Object> body = Map.of(
                "email", "dupe@acme.test", "password", "learner1234");

        // A trainee cannot mint accounts.
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + playerOneToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        // A short initial credential is refused.
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", "short@acme.test", "password", "short"))))
                .andExpect(status().isBadRequest());

        // The same email twice in one tenant is a conflict, not a second account.
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isConflict());

        // A facilitator may not create another facilitator; an admin may (D21: one per cohort).
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", "trainer9@acme.test", "password", "learner1234",
                                "role", "FACILITATOR"))))
                .andExpect(status().isForbidden());

        user(tenantId, "boss@acme.test", "ADMIN");
        String admin = loginOn("acme", "boss@acme.test");
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", "trainer9@acme.test", "password", "learner1234",
                                "role", "FACILITATOR"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("FACILITATOR"));
    }

    @Test
    void a_trainee_cannot_read_a_rival_companys_numbers() throws Exception {
        openRound();

        // Cash, stock, decisions and the P&L are the competitive information the simulation is about:
        // another seat's numbers must not be readable by changing the team id in the URL.
        for (String path : List.of("context", "statements", "market-report")) {
            mvc.perform(get("/api/runs/" + runId + "/teams/" + teamTwo + "/" + path)
                            .header("Authorization", "Bearer " + playerOneToken))
                    .andExpect(status().isForbidden());
        }

        // Their own company still opens, and the facilitator runs the whole class.
        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamOne + "/context")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamTwo + "/context")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());
    }

    /**
     * Before the facilitator opens round 1 the run sits at round 0 and there is nothing to decide.
     * The context says so explicitly (`roundOpen: false`) instead of leaving the trainee's screen to
     * render a full decision form with a "Submit round 0" button that the server refuses
     * (`round.not_open`).
     */
    @Test
    void the_context_says_no_round_is_open_before_the_first_one() throws Exception {
        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamOne + "/context")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.round").value(0))
                .andExpect(jsonPath("$.roundOpen").value(false));

        // And the server refuses the submission the old screen offered.
        submit(playerOneToken, teamOne, payload(600, 150, 30, 0L, 30, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("round.not_open"));

        openRound();

        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamOne + "/context")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.round").value(1))
                .andExpect(jsonPath("$.roundOpen").value(true));
    }

    @Test
    void the_form_context_reports_what_was_already_submitted() throws Exception {
        openRound();
        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamOne + "/context")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(false))
                .andExpect(jsonPath("$.decision").doesNotExist());

        submit(playerOneToken, teamOne, payload(600, 150, 30, 0L, 30, 0)).andExpect(status().isOk());

        // The trainee has to be able to see and correct what they sent: the decision is an upsert
        // until the round closes, and a reload must not look like an empty month.
        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamOne + "/context")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(true))
                .andExpect(jsonPath("$.decision.production[0].product").value("P1"))
                .andExpect(jsonPath("$.decision.production[0].units").value(600))
                .andExpect(jsonPath("$.decision.manpower.production").value(30));

        // Another company's submission is not visible here.
        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamTwo + "/context")
                        .header("Authorization", "Bearer " + playerTwoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(false));
    }

    /**
     * D34: the statement carries one line per product — opening, produced, demand, sold, lost,
     * closing — and those lines are the same numbers as the statement totals, not a second
     * calculation. This is what lets a debrief answer "which product lost the 82 pieces?".
     */
    @Test
    void the_statement_carries_the_per_product_breakdown() throws Exception {
        openRound();
        submit(playerOneToken, teamOne, payload(600, 150, 30, 0L, 30, 0)).andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());

        MvcResult result = mvc.perform(get("/api/runs/" + runId + "/teams/" + teamOne + "/statements")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode statement = json.readTree(result.getResponse().getContentAsString()).get(0);
        JsonNode products = statement.get("detail").get("products");

        assertThat(products.size()).isGreaterThan(0);
        BigDecimal sold = BigDecimal.ZERO;
        BigDecimal lost = BigDecimal.ZERO;
        BigDecimal revenue = BigDecimal.ZERO;
        for (JsonNode line : products) {
            // Demand is allocated in whole pieces (D34), so nothing on the shelf is a fraction.
            assertThat(line.get("demand").decimalValue().stripTrailingZeros().scale())
                    .as("whole pieces for " + line.get("product").asText())
                    .isLessThanOrEqualTo(0);
            assertThat(line.get("sold").decimalValue().add(line.get("lost").decimalValue()))
                    .as("sold + lost = demand for " + line.get("product").asText())
                    .isEqualByComparingTo(line.get("demand").decimalValue());
            sold = sold.add(line.get("sold").decimalValue());
            lost = lost.add(line.get("lost").decimalValue());
            revenue = revenue.add(line.get("revenue").decimalValue());
        }
        assertThat(lost).isEqualByComparingTo(statement.get("lost_sales").decimalValue());
        assertThat(revenue).isEqualByComparingTo(statement.get("revenue").decimalValue());
        assertThat(sold).isGreaterThan(BigDecimal.ZERO);
    }

    /**
     * D35: a finished project must not lock the class out. The trainee's form sends a row for every
     * project every round, so once one project completed, every submission was refused with
     * `rnd.not_active` — including rounds that had nothing to do with that project.
     */
    @Test
    void a_finished_project_does_not_block_the_next_round() throws Exception {
        openRound();
        // RND-P1-COST costs 300M over 3 months: 100M a month completes it as round 3 is computed.
        for (int month = 1; month <= 3; month++) {
            submit(playerOneToken, teamOne, payload(600, 150, 30, 0L, 30, 100_000_000L))
                    .andExpect(status().isOk());
            advance();
        }

        // Round 4: the form still carries RND-P1-COST, with 0. That must be accepted.
        submit(playerOneToken, teamOne, payloadEveryProject(600, 150, 30, 0L, 30, 0L))
                .andExpect(status().isOk());

        // Money into a finished project is still refused, and the code names the project.
        submit(playerOneToken, teamOne, payloadEveryProject(600, 150, 30, 0L, 30, 100_000_000L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("decision.invalid"))
                .andExpect(jsonPath("$.problems[0].code").value("rnd.not_active"))
                .andExpect(jsonPath("$.problems[0].params.project").value("RND-P1-COST"));
    }

    private static Map<String, Object> payloadEveryProject(int p1, int p2, int p3, long marketing,
                                                           int production, long rnd) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(
                payload(p1, p2, p3, marketing, production, 0L));
        body.put("rnd", List.of(
                Map.of("project", "RND-P1-COST", "invest", rnd),
                Map.of("project", "RND-P2-COST", "invest", 0L),
                Map.of("project", "RND-P3-QUAL", "invest", 0L),
                Map.of("project", "RND-CAP", "invest", 0L),
                Map.of("project", "RND-MKT-P1", "invest", 0L)));
        return body;
    }

    @Test
    void three_months_in_sequence_accumulate_state_without_drift() throws Exception {
        // This test guards the BASE economy, so it plays a calm one: the event deck is drawn once per
        // run from the run's id (D18), and a cost shock (x1.12 for two months) can legitimately turn a
        // thin but healthy month into a small loss -- a fixed cash threshold would then depend on how
        // many runs earlier test classes happened to create. The deck's own behaviour is covered by
        // EnvironmentTest. The schedule is written when the run is created, so clearing it here is the
        // whole mute.
        jdbc.update("delete from run_environment_event where run_id = ?", runId);

        // RND-P1-COST costs 300M over 3 months: 100M a month must complete it exactly at month 3.
        openRound();
        for (int month = 1; month <= 3; month++) {
            submit(playerOneToken, teamOne, payload(600, 150, 30, 20_000_000L, 30, 100_000_000L))
                    .andExpect(status().isOk());
            if (month < 3) {
                advance();
            }
        }
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());

        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamOne + "/statements")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        Map<String, Object> project = jdbc.queryForMap("""
                select status, benefit_factor, months_invested from company_rnd
                where team_id = ? and project_code = 'RND-P1-COST'
                """, teamOne);
        assertThat(project.get("status")).isEqualTo("COMPLETED");
        assertThat((java.math.BigDecimal) project.get("benefit_factor")).isEqualByComparingTo("1.0000");
        assertThat(project.get("months_invested")).isEqualTo(3);

        Map<String, Object> team = jdbc.queryForMap(
                "select cash, brand_score, cumulative_profit, cumulative_revenue from team where id = ?", teamOne);
        // A company that sells at capacity with a sane cost base is profitable: cash grows, it does
        // not drain. This is the calibration guard, not a business assertion.
        assertThat((java.math.BigDecimal) team.get("cash")).isGreaterThan(new java.math.BigDecimal("2000000000"));
        assertThat((java.math.BigDecimal) team.get("cumulative_profit")).isGreaterThan(java.math.BigDecimal.ZERO);
        assertThat((java.math.BigDecimal) team.get("cumulative_revenue")).isGreaterThan(new java.math.BigDecimal("2000000000"));
        // Three months of 20M in digital: brand rises, it does not spike.
        assertThat((java.math.BigDecimal) team.get("brand_score")).isGreaterThan(new java.math.BigDecimal("50"));
    }

    @Test
    void the_run_list_is_scoped_to_the_tenant() throws Exception {
        mvc.perform(get("/api/runs").header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value((int) runId))
                .andExpect(jsonPath("$[0].teams.length()").value(2));

        // Another tenant must see an empty list, not this tenant's runs.
        long other = jdbc.queryForObject(
                "insert into tenant (code, name) values ('other', 'Other') returning id", Long.class);
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, 'boss@other.test', ?, 'boss', 'FACILITATOR')
                """, other, encoder.encode("pw-boss@other.test"));
        String otherToken = loginOn("other", "boss@other.test");

        mvc.perform(get("/api/runs").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void the_market_report_is_paywalled_and_then_readable() throws Exception {
        openRound();

        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamOne + "/market-report")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isPaymentRequired());

        submit(playerOneToken, teamOne, payload(600, 150, 30, 0L, 30, 0, true))
                .andExpect(status().isOk());

        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamOne + "/market-report")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cost").value(15000000))
                .andExpect(jsonPath("$.forecast.length()").value(2))
                .andExpect(jsonPath("$.current.demandIndex").isNumber());

        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());

        java.math.BigDecimal reportCost = jdbc.queryForObject(
                "select report_cost from round_statement where team_id = ?", java.math.BigDecimal.class, teamOne);
        assertThat(reportCost).isEqualByComparingTo("15000000");
    }

    @Test
    void cycles_are_configurable_and_rounds_can_exceed_the_default() throws Exception {
        // D21: this facilitator already has a simulation in progress in this fixture.
        mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(idOf("trainer@acme.test")))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Second at once", "scenarioKey", "standard-v1",
                                "teams", List.of(Map.of("name", "B", "companyName", "B Inc"))))))
                .andExpect(status().isConflict());

        // A second simulation therefore needs a second facilitator — which is the point of the rule.
        user(tenantId, "trainer2@acme.test", "FACILITATOR");
        String secondFacilitator = loginOn("acme", "trainer2@acme.test");

        MvcResult created = mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(idOf("trainer2@acme.test")))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Three cycles", "scenarioKey", "standard-v1", "cycles", 3,
                                "teams", List.of(Map.of("name", "A", "companyName", "A Inc"))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rounds").value(12))
                .andExpect(jsonPath("$.cycles").value(3))
                .andReturn();
        long newRun = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        // Twelve rounds of economy were generated, and the third cycle carries the drift.
        Integer generated = jdbc.queryForObject(
                "select count(*) from run_round_economy where run_id = ?", Integer.class, newRun);
        assertThat(generated).isEqualTo(12);
        java.math.BigDecimal roundNine = jdbc.queryForObject(
                "select demand_index from run_round_economy where run_id = ? and round_number = 9",
                java.math.BigDecimal.class, newRun);
        assertThat(roundNine).isEqualByComparingTo("1.040");

        // Past the eight-round default: every round must still open, and the thirteenth must not.
        // D57: each later round needs the previous one computed first.
        mvc.perform(post("/api/runs/" + newRun + "/rounds/next")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
        for (int i = 2; i <= 12; i++) {
            mvc.perform(post("/api/runs/" + newRun + "/rounds/close")
                    .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
            mvc.perform(post("/api/runs/" + newRun + "/rounds/next")
                    .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
        }
        mvc.perform(post("/api/runs/" + newRun + "/rounds/next")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isConflict());
    }

    @Test
    void computing_the_last_round_finishes_the_run_and_frees_the_facilitator() throws Exception {
        // D21 refuses a second simulation while one is unfinished, so this scenario gets its own
        // facilitator; that is also what lets the test prove the guard is released afterwards.
        user(tenantId, "trainer5@acme.test", "FACILITATOR");
        String trainer = loginOn("acme", "trainer5@acme.test");

        MvcResult created = mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(idOf("trainer5@acme.test")))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "One round", "scenarioKey", "standard-v1", "rounds", 1,
                                "teams", List.of(Map.of("name", "Solo", "companyName", "Solo Inc"))))))
                .andExpect(status().isCreated())
                .andReturn();
        long shortRun = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/runs/" + shortRun + "/rounds/next")
                        .header("Authorization", "Bearer " + trainer))
                .andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + shortRun + "/rounds/close")
                        .header("Authorization", "Bearer " + trainer))
                .andExpect(status().isOk());

        // Round 1 of 1 computed: the run leaves OPEN, and the finish is stamped.
        mvc.perform(get("/api/runs/" + shortRun).header("Authorization", "Bearer " + trainer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINISHED"))
                .andExpect(jsonPath("$.currentRound").value(1));
        assertThat(jdbc.queryForObject("select finished_at from run where id = ?",
                java.sql.Timestamp.class, shortRun)).isNotNull();

        // A finished run is no longer "in flight", so the same facilitator may start the next one.
        mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(idOf("trainer5@acme.test")))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "The next cohort", "scenarioKey", "standard-v1",
                                "teams", List.of(Map.of("name", "Next", "companyName", "Next Inc"))))))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * D75: the class table ranks by cash after the round, then by that round's profit. Cash and profit
     * are set so the two orderings disagree — the failure this test is here to catch is a table that
     * still sorts on profit while showing cash.
     */
    @Test
    void the_class_table_ranks_by_cash_then_by_that_rounds_profit() throws Exception {
        openRound();
        submit(playerOneToken, teamOne, payload(600, 150, 30, 20_000_000L, 30, 0))
                .andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());

        // More cash, less profit: liquidity wins.
        score(teamOne, "1600000000", "10000000");
        score(teamTwo, "1500000000", "90000000");

        JsonNode first = standings();
        assertThat(first).hasSize(2);
        assertThat(first.get(0).get("teamId").asLong()).isEqualTo(teamOne);
        assertThat(first.get(0).get("rank").asInt()).isEqualTo(1);
        assertThat(first.get(0).get("cash").decimalValue()).isEqualByComparingTo("1600000000");
        assertThat(first.get(0).get("profit").decimalValue()).isEqualByComparingTo("10000000");
        assertThat(first.get(1).get("teamId").asLong()).isEqualTo(teamTwo);

        // Same cash: the round's profit breaks the tie.
        score(teamOne, "1500000000", "10000000");
        score(teamTwo, "1500000000", "90000000");

        JsonNode second = standings();
        assertThat(second.get(0).get("teamId").asLong()).isEqualTo(teamTwo);
        assertThat(second.get(0).get("rank").asInt()).isEqualTo(1);
        assertThat(second.get(1).get("rank").asInt()).isEqualTo(2);
    }

    private JsonNode standings() throws Exception {
        MvcResult result = mvc.perform(get("/api/runs/" + runId + "/standings")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** Rewrites one company's cash and profit for round 1, to force the ordering under test. */
    private void score(long teamId, String cash, String profit) {
        jdbc.update("""
                update round_result
                set score = jsonb_set(jsonb_set(score, '{cash}', to_jsonb(?::numeric)),
                                      '{profit}', to_jsonb(?::numeric))
                where team_id = ? and round_id = (select id from round where run_id = ? and number = 1)
                """, cash, profit, teamId, runId);
    }

    /**
     * D77: buying machines costs money and buys one worker of capacity each, and it is cash rather
     * than a cost — which is exactly why it shows up in the statement as 库存与产能变动. Sent as a
     * *count* (what the console used to send) it bought nothing and charged single currency units;
     * this is the test that was missing. Machines are installed for the **next** month: the engine
     * plans with the ones the company already owns, so a purchase today cannot fix today's capacity.
     */
    @Test
    void buying_machines_charges_its_cost_and_adds_capacity_but_never_touches_profit() throws Exception {
        openRound();
        Map<String, Object> withMachines = new java.util.LinkedHashMap<>(payload(600, 150, 30, 0L, 30, 0));
        withMachines.put("capacityPurchase", 3L * 40_000_000L);
        submit(playerOneToken, teamOne, withMachines).andExpect(status().isOk());
        submit(playerTwoToken, teamTwo, payload(600, 150, 30, 0L, 30, 0)).andExpect(status().isOk());

        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());

        Map<String, Object> one = statement(1, teamOne);
        Map<String, Object> two = statement(1, teamTwo);

        assertThat((BigDecimal) one.get("capacity_cost")).isEqualByComparingTo("120000000");
        assertThat((BigDecimal) one.get("machine_workers")).isEqualByComparingTo("3");
        // Cash only: the purchase changes the bank, never the profit line.
        assertThat((BigDecimal) one.get("profit")).isEqualByComparingTo((BigDecimal) two.get("profit"));
        assertThat(((BigDecimal) two.get("cash_after")).subtract((BigDecimal) one.get("cash_after")))
                .isEqualByComparingTo("120000000");
        // Installed for next month, so this month plans with the staff it already had.
        assertThat((BigDecimal) one.get("capacity_available")).isEqualByComparingTo("30");

        // ...and next month the machines are on the floor.
        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());
        submit(playerOneToken, teamOne, payload(0, 0, 0, 0L, 30, 0)).andExpect(status().isOk());
        submit(playerTwoToken, teamTwo, payload(0, 0, 0, 0L, 30, 0)).andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());

        assertThat((BigDecimal) statement(2, teamOne).get("capacity_available"))
                .as("30 production staff + 3 machines installed last month").isEqualByComparingTo("33");
        assertThat((BigDecimal) statement(2, teamTwo).get("capacity_available")).isEqualByComparingTo("30");
    }

    /**
     * D79, end to end: a company builds stock in one month and sells it in later ones. Once the shelf
     * is empty again, the cash movement over the whole term must equal the cumulative profit exactly.
     * Under the old replacement-cost basis it never did: carried stock was expensed at the current
     * month's price instead of the price actually paid, so a loss-making term could end with more cash
     * than it started with.
     */
    @Test
    void a_term_that_ends_with_no_stock_has_cash_equal_to_profit() throws Exception {
        openRound();
        // More than this market takes in a month, so part of it sits on the shelf over the boundary.
        submit(playerOneToken, teamOne, payload(900, 0, 0, 0L, 30, 0)).andExpect(status().isOk());
        submit(playerTwoToken, teamTwo, payload(0, 0, 0, 0L, 30, 0)).andExpect(status().isOk());
        close();

        // Draw the shelf down: produce nothing more, round after round, until it is empty.
        for (int round = 0; round < 4 && closingStock(teamOne).signum() > 0; round++) {
            mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                    .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
            submit(playerOneToken, teamOne, payload(0, 0, 0, 0L, 30, 0)).andExpect(status().isOk());
            submit(playerTwoToken, teamTwo, payload(0, 0, 0, 0L, 30, 0)).andExpect(status().isOk());
            close();
        }

        JsonNode rows = statementsOf(teamOne);
        BigDecimal opening = new BigDecimal(rows.get(0).path("opening_cash").asText());
        BigDecimal closing = new BigDecimal(rows.get(rows.size() - 1).path("cash_after").asText());
        BigDecimal profit = BigDecimal.ZERO;
        for (JsonNode row : rows) {
            profit = profit.add(new BigDecimal(row.path("profit").asText()));
        }
        assertThat(closingStock(teamOne)).as("the shelf is empty, so nothing is left to value")
                .isEqualByComparingTo("0");
        assertThat(closing.subtract(opening))
                .as("cash movement equals cumulative profit when no stock is carried across")
                .isEqualByComparingTo(profit);
    }

    private void close() throws Exception {
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
    }

    /** What is on the shelf after the last computed round: every product, not just the last row. */
    private BigDecimal closingStock(long teamId) throws Exception {
        JsonNode rows = statementsOf(teamId);
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode line : rows.get(rows.size() - 1).path("detail").path("products")) {
            total = total.add(new BigDecimal(line.path("closingStock").asText()));
        }
        return total;
    }

    private JsonNode statementsOf(long teamId) throws Exception {
        MvcResult result = mvc.perform(get("/api/runs/" + runId + "/teams/" + teamId + "/statements")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, Object> statement(int round, long teamId) {
        return jdbc.queryForMap("""
                select s.capacity_cost, s.capacity_available, s.profit, s.cash_after, t.machine_workers
                from round_statement s
                join team t on t.id = s.team_id
                join round r on r.id = s.round_id
                where r.run_id = ? and r.number = ? and s.team_id = ?
                """, runId, round, teamId);
    }

    /** What the console does between rounds: compute the open one, then open the next (D57). */
    private void advance() throws Exception {
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions submit(String token, long teamId,
                                                                      Map<String, Object> body)
            throws Exception {
        return mvc.perform(post("/api/runs/" + runId + "/teams/" + teamId + "/decisions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private static Map<String, Object> payload(int p1, int p2, int p3, long marketing, int production,
                                               long rnd) {
        return payload(p1, p2, p3, marketing, production, rnd, false);
    }

    private static Map<String, Object> payload(int p1, int p2, int p3, long marketing, int production,
                                               long rnd, boolean marketReport) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("marketReport", marketReport);
        body.putAll(Map.of(
                "production", List.of(
                        Map.of("product", "P1", "units", p1),
                        Map.of("product", "P2", "units", p2),
                        Map.of("product", "P3", "units", p3)),
                "price", List.of(
                        Map.of("product", "P1", "unitPrice", 1_000_000),
                        Map.of("product", "P2", "unitPrice", 2_500_000),
                        Map.of("product", "P3", "unitPrice", 5_000_000)),
                "marketing", List.of(Map.of("channel", "DIGITAL", "spend", marketing)),
                "manpower", Map.of("production", production),
                "rnd", List.of(Map.of("project", "RND-P1-COST", "invest", rnd)),
                "capacityPurchase", 0));
        return body;
    }

    @Test
    void failures_carry_codes_so_the_interface_owns_the_wording() throws Exception {
        openRound();

        // A rule the trainee can fix: the code names the rule and the product, never a sentence.
        Map<String, Object> badPrice = new java.util.LinkedHashMap<>(payload(10, 0, 0, 0L, 30, 0));
        badPrice.put("price", List.of(Map.of("product", "P1", "unitPrice", 0)));
        submit(playerOneToken, teamOne, badPrice)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("decision.invalid"))
                .andExpect(jsonPath("$.problems[0].code").value("price.not_positive"))
                .andExpect(jsonPath("$.problems[0].params.product").value("P1"))
                // the English text still travels, as a fallback for logs and older clients
                .andExpect(jsonPath("$.details[0]").isString());

        // Money: the shortfall lines are codes too, so each can be labelled in the reader's language.
        submit(playerOneToken, teamOne, payload(600, 150, 30, 5_000_000_000L, 30, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("decision.cash_shortfall"))
                .andExpect(jsonPath("$.lines.marketing").exists());

        // Addressing and role failures carry codes as well.
        mvc.perform(get("/api/runs/999999").header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("run.not_found"));
        mvc.perform(delete("/api/runs/" + runId).header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.facilitator_only"));
    }

    @Test
    void a_facilitator_sees_only_their_own_simulations() throws Exception {
        user(tenantId, "trainer3@acme.test", "FACILITATOR");
        String third = loginOn("acme", "trainer3@acme.test");

        mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(idOf("trainer3@acme.test")))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Theirs", "scenarioKey", "standard-v1",
                                "teams", List.of(Map.of("name", "Z", "companyName", "Z Inc"))))))
                .andExpect(status().isCreated());

        // The original facilitator still sees exactly their own simulation.
        mvc.perform(get("/api/runs").header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value((int) runId));
    }

    @Test
    void the_scoreboard_is_one_row_per_company_for_a_single_round() throws Exception {
        openRound();
        submit(playerOneToken, teamOne, payload(600, 150, 30, 0L, 30, 0)).andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());

        openRound();
        submit(playerOneToken, teamOne, payload(500, 120, 20, 0L, 30, 0)).andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());

        // Two rounds computed, but the board is not a history: one row per company, latest round.
        MvcResult latest = mvc.perform(get("/api/runs/" + runId + "/scoreboard")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].round").value(2))
                .andExpect(jsonPath("$[1].round").value(2))
                .andReturn();
        assertThat(latest.getResponse().getContentAsString()).doesNotContain("\"round\":1");

        // A named round is still available, for the debrief.
        mvc.perform(get("/api/runs/" + runId + "/scoreboard").param("round", "1")
                        .header("Authorization", "Bearer " + playerOneToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].round").value(1));
    }

    private void openRound() throws Exception {
        openRound(runId);
    }

    private void openRound(long id) throws Exception {
        mvc.perform(post("/api/runs/" + id + "/rounds/next")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
    }

    private JsonNode createRun() throws Exception {
        MvcResult result = mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(idOf("trainer@acme.test")))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Cohort",
                                "scenarioKey", "standard-v1",
                                "teams", List.of(
                                        Map.of("name", "Alpha", "companyName", "Alpha Inc"),
                                        Map.of("name", "Beta", "companyName", "Beta Inc"))))))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void addPlayer(long teamId, String email) throws Exception {
        Long userId = jdbc.queryForObject("select id from app_user where email = ?", Long.class, email);
        mvc.perform(post("/api/runs/" + runId + "/players")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "teamId", teamId, "userId", userId, "roleKey", "OWNER"))))
                .andExpect(status().isCreated());
    }

    private static java.math.BigDecimal profit(List<Map<String, Object>> board, long teamId) {
        return board.stream().filter(row -> ((Number) row.get("team_id")).longValue() == teamId)
                .map(row -> (java.math.BigDecimal) row.get("profit")).findFirst().orElseThrow();
    }

    private void user(long tenantId, String email, String role) {
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?)
                """, tenantId, email, encoder.encode("pw-" + email), email, role);
    }

    private String loginOn(String tenantCode, String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", tenantCode, "email", email,
                                "password", "pw-" + email))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private long idOf(String email) {
        return jdbc.queryForObject("select id from app_user where email = ?", Long.class, email);
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
