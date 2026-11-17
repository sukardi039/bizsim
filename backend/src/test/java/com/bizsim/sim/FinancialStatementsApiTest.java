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
 * D88: the three reports on the review page. They are read from the statements the console already
 * shows, so the test proves two things at once — that the arithmetic ties on real computed rounds, and
 * that a trainee still cannot read another company's books.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FinancialStatementsApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private InitDataService initData;

    private long tenantId;
    private long runId;
    private List<Long> teams;
    private BigDecimal startingCash;

    private void clean() {
        for (String table : List.of("round_statement", "round_result", "decision", "round", "player",
                "company_product", "company_staff", "company_rnd", "team", "run", "simulation")) {
            jdbc.update("delete from " + table);
        }
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

        // Three computed rounds, deliberately more than one: cumulative machinery and retained
        // earnings only have something to prove from round 2 onwards.
        InitDataService.Summary summary = initData.init("books",
                new InitDataService.Params(3, 3, 3, null, "en", "books-pass-1", null, null, null));
        tenantId = summary.tenantId();
        runId = summary.runId();
        teams = jdbc.queryForList("select id from team where run_id = ? order by id", Long.class, runId);
        assertThat(teams).hasSize(3);
        startingCash = jdbc.queryForObject("""
                select s.opening_cash from round_statement s join round r on r.id = s.round_id
                where r.run_id = ? and s.team_id = ? order by r.number limit 1
                """, BigDecimal.class, runId, teams.get(0));
    }

    @Test
    void every_round_balances_and_the_cash_flow_ties_to_the_bank() throws Exception {
        long team = teams.get(0);
        JsonNode books = financials(login("trainer@books.test"), team);

        assertThat(books.get("rounds")).hasSize(3);
        BigDecimal cash = null;
        for (JsonNode round : books.get("rounds")) {
            BigDecimal pnlProfit = amount(round.get("pnl"), "pnl.profit");
            BigDecimal storedProfit = storedProfit(team, round.get("round").asInt());
            assertThat(pnlProfit).as("profit and loss agrees with the stored profit")
                    .isEqualByComparingTo(storedProfit);

            BigDecimal net = amount(round.get("cashFlow"), "cash.netChange");
            BigDecimal operating = amount(round.get("cashFlow"), "cash.operatingTotal");
            BigDecimal investing = amount(round.get("cashFlow"), "cash.investingTotal");
            BigDecimal financing = amount(round.get("cashFlow"), "cash.financing");
            BigDecimal revaluation = amount(round.get("cashFlow"), "cash.revaluation");
            BigDecimal opening = amount(round.get("cashFlow"), "cash.opening");
            BigDecimal closing = amount(round.get("cashFlow"), "cash.closing");

            assertThat(net).as("net movement is the movement in the bank")
                    .isEqualByComparingTo(closing.subtract(opening));
            assertThat(operating.add(investing).add(financing).add(revaluation))
                    .as("the sections add up to the movement")
                    .isEqualByComparingTo(net);
            assertThat(revaluation).as("weighted-average statements need no revaluation line")
                    .isEqualByComparingTo(BigDecimal.ZERO);

            BigDecimal difference = amount(round.get("balance"), "balance.difference");
            assertThat(difference).as("assets = liabilities + equity").isEqualByComparingTo(BigDecimal.ZERO);

            BigDecimal assets = amount(round.get("balance"), "balance.assetsTotal");
            BigDecimal liabilities = amount(round.get("balance"), "balance.liabilitiesTotal");
            BigDecimal equity = amount(round.get("balance"), "balance.equityTotal");
            assertThat(assets).isEqualByComparingTo(
                    amount(round.get("balance"), "balance.cash")
                            .add(amount(round.get("balance"), "balance.inventory"))
                            .add(amount(round.get("balance"), "balance.machines")));
            assertThat(liabilities.add(equity)).isEqualByComparingTo(assets);

            // The balance sheet's cash line is the statement's cash line, not a second computation.
            assertThat(amount(round.get("balance"), "balance.cash")).isEqualByComparingTo(closing);
            cash = closing;
        }

        assertThat(cash).isNotNull();
        assertThat(cash).as("the last closing cash is the last statement's cash")
                .isEqualByComparingTo(jdbc.queryForObject("""
                        select s.cash_after from round_statement s join round r on r.id = s.round_id
                        where r.run_id = ? and s.team_id = ? order by r.number desc limit 1
                        """, BigDecimal.class, runId, team));
    }

    @Test
    void the_balance_sheet_carries_capital_machines_and_retained_earnings() throws Exception {
        long team = teams.get(0);
        JsonNode rounds = financials(login("trainer@books.test"), team).get("rounds");

        BigDecimal cumulativeProfit = BigDecimal.ZERO;
        BigDecimal cumulativeCapacity = BigDecimal.ZERO;
        BigDecimal cumulativeRefund = BigDecimal.ZERO;
        for (JsonNode round : rounds) {
            int number = round.get("round").asInt();
            JsonNode balance = round.get("balance");

            cumulativeProfit = cumulativeProfit.add(storedProfit(team, number));
            cumulativeCapacity = cumulativeCapacity.add(jdbc.queryForObject("""
                    select s.capacity_cost from round_statement s join round r on r.id = s.round_id
                    where r.run_id = ? and s.team_id = ? and r.number = ?
                    """, BigDecimal.class, runId, team, number));
            cumulativeRefund = cumulativeRefund.add(storedRefund(team, number));

            assertThat(amount(balance, "balance.capital"))
                    .as("paid-in capital is what the owners put in").isEqualByComparingTo(startingCash);
            assertThat(amount(balance, "balance.retained"))
                    .as("retained earnings is the profit of every round so far")
                    .isEqualByComparingTo(cumulativeProfit);
            assertThat(amount(balance, "balance.machines"))
                    .as("machines sit at cost: every purchase, never depreciated")
                    .isEqualByComparingTo(cumulativeCapacity);
            assertThat(amount(balance, "balance.refunds"))
                    .as("refunds are cash the profit and loss never counted").isEqualByComparingTo(cumulativeRefund);
        }
    }

    @Test
    void every_row_is_a_translation_key_the_console_can_label() throws Exception {
        JsonNode round = financials(login("trainer@books.test"), teams.get(0)).get("rounds").get(0);

        for (String block : List.of("pnl", "cashFlow", "balance")) {
            assertThat(round.get(block).size()).as("%s has rows", block).isGreaterThan(0);
            for (JsonNode row : round.get(block)) {
                String key = row.get("key").asText();
                assertThat(key).as("%s row %s carries a prefix", block, key).startsWith(block.equals("cashFlow") ? "cash." : block + ".");
                assertThat(row.get("level").asInt()).as("a level the page knows").isBetween(0, 2);
            }
        }
    }

    @Test
    void a_trainee_reads_their_own_books_and_only_their_own() throws Exception {
        long own = teamOf("player1@books.test");
        long other = teams.stream().filter(id -> id != own).findFirst().orElseThrow();
        String token = login("player1@books.test");

        mvc.perform(get("/api/runs/" + runId + "/teams/" + own + "/financials")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rounds").isArray());

        mvc.perform(get("/api/runs/" + runId + "/teams/" + other + "/financials")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("team.not_yours"));
    }

    @Test
    void a_session_with_nothing_computed_reports_no_rounds() throws Exception {
        InitDataService.Summary fresh = initData.init("freshbooks",
                new InitDataService.Params(2, 2, 0, null, "en", "fresh-pass-1", null, null, null));
        long team = jdbc.queryForObject("select id from team where run_id = ? order by id limit 1",
                Long.class, fresh.runId());

        mvc.perform(get("/api/runs/" + fresh.runId() + "/teams/" + team + "/financials")
                        .header("Authorization", "Bearer " + loginOn("freshbooks", "trainer@freshbooks.test",
                                "fresh-pass-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rounds").isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode financials(String token, long team) throws Exception {
        MvcResult result = mvc.perform(get("/api/runs/" + runId + "/teams/" + team + "/financials")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** The amount of one row, or zero when the report does not carry that row on this round. */
    private static BigDecimal amount(JsonNode rows, String key) {
        for (JsonNode row : rows) {
            if (key.equals(row.get("key").asText())) {
                return row.get("amount").decimalValue();
            }
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal storedProfit(long team, int round) {
        return jdbc.queryForObject("""
                select s.profit from round_statement s join round r on r.id = s.round_id
                where r.run_id = ? and s.team_id = ? and r.number = ?
                """, BigDecimal.class, runId, team, round);
    }

    private BigDecimal storedRefund(long team, int round) {
        BigDecimal refund = jdbc.queryForObject("""
                select coalesce((s.detail->>'refund')::numeric, 0)
                from round_statement s join round r on r.id = s.round_id
                where r.run_id = ? and s.team_id = ? and r.number = ?
                """, BigDecimal.class, runId, team, round);
        return refund == null ? BigDecimal.ZERO : refund;
    }

    private long teamOf(String email) {
        return jdbc.queryForObject("""
                select p.team_id from player p join app_user u on u.id = p.user_id
                join team t on t.id = p.team_id
                where u.email = ? and t.run_id = ?
                """, Long.class, email, runId);
    }

    private String login(String email) throws Exception {
        return loginOn("books", email, "books-pass-1");
    }

    private String loginOn(String tenantCode, String email, String pass) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", tenantCode, "email", email, "password", pass))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
