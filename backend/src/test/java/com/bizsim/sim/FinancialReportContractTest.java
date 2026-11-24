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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D93: the contract of the three reports, checked line by line.
 *
 * <p>{@code FinancialStatementsApiTest} proves the reports agree with the stored statements. This test
 * proves the reports agree <b>with themselves</b>: that a subtotal is the sum of the lines above it,
 * that the three cash-flow sections add up to the movement, that a balance sheet balances, and that
 * every row is something the console can label — a key with a known prefix, a known section, a level
 * of 0, 1 or 2, and no duplicate.
 *
 * <p>It runs over every company and every computed round of a seeded session, so a change that breaks
 * one row of one report fails here rather than in a classroom.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FinancialReportContractTest extends PostgresTestBase {

    private static final BigDecimal CENT = new BigDecimal("0.01");

    private static final List<String> PNL_SECTIONS = List.of("revenue", "expenses", "total");
    private static final List<String> CASH_SECTIONS =
            List.of("operating", "investing", "financing", "movement");
    private static final List<String> BALANCE_SECTIONS =
            List.of("assets", "liabilities", "equity", "check");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private InitDataService initData;

    private long runId;
    private List<Long> teams;

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
        jdbc.update("delete from simulation_package");
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
        InitDataService.Summary summary = initData.init("reports",
                new InitDataService.Params(3, 3, 3, null, "en", "reports-pass-1", null, null, null));
        runId = summary.runId();
        teams = jdbc.queryForList("select id from team where run_id = ? order by id", Long.class, runId);
        assertThat(teams).hasSize(3);
    }

    @Test
    void every_line_of_every_report_obeys_the_contract() throws Exception {
        String trainer = login("trainer@reports.test");
        int rounds = 0;

        for (long team : teams) {
            JsonNode books = financials(trainer, team);
            Map<Integer, JsonNode> statements = statementsByRound(trainer, team);

            for (JsonNode round : books.get("rounds")) {
                int number = round.get("round").asInt();
                rounds++;
                String where = "team " + team + " round " + number;

                shape(where, "pnl", round.get("pnl"), PNL_SECTIONS, "pnl.");
                shape(where, "cash", round.get("cashFlow"), CASH_SECTIONS, "cash.");
                shape(where, "balance", round.get("balance"), BALANCE_SECTIONS, "balance.");

                JsonNode stored = statements.get(number);
                assertThat(stored).as(where + " has a stored statement").isNotNull();

                // D97: every product line carries its share of that product's market, and a share is a
                // percentage of a pool — never negative, never above the whole market.
                for (JsonNode product : stored.get("detail").path("products")) {
                    assertThat(product.has("share"))
                            .as(where + " " + product.get("product").asText() + " carries a market share")
                            .isTrue();
                    assertThat(product.get("share").decimalValue())
                            .as(where + " " + product.get("product").asText() + " share is a percentage")
                            .isBetween(BigDecimal.ZERO, new BigDecimal("100"));
                }

                profitAndLoss(where, round.get("pnl"), stored);
                cashFlow(where, round.get("cashFlow"), stored);
                balanceSheet(where, round.get("balance"), stored);
            }
        }

        assertThat(rounds).as("every company and round was read").isEqualTo(teams.size() * 3);
    }

    /**
     * Structure: a key the console can label, a section it knows, a level it can rule, no duplicates,
     * and the sections in the order a reader expects.
     */
    private void shape(String where, String block, JsonNode rows, List<String> sections, String prefix) {
        Set<String> keys = new HashSet<>();
        int previous = -1;
        for (JsonNode row : rows) {
            String key = row.get("key").asText();
            String section = row.get("section").asText();
            int level = row.get("level").asInt();

            assertThat(key).as(where + " " + block + " key is a catalogue key").startsWith(prefix);
            assertThat(keys.add(key)).as(where + " " + block + " key " + key + " appears once").isTrue();
            assertThat(sections).as(where + " " + block + " section " + section).contains(section);
            assertThat(level).as(where + " " + block + " level of " + key).isBetween(0, 2);

            int position = sections.indexOf(section);
            assertThat(position).as(where + " " + block + " sections stay in order")
                    .isGreaterThanOrEqualTo(previous);
            previous = position;
        }
        assertThat(rows).as(where + " " + block + " is not empty").isNotEmpty();
    }

    private void profitAndLoss(String where, JsonNode rows, JsonNode stored) {
        Money p = new Money(rows);
        money(where + " revenue less cost of sales is gross profit",
                p.get("pnl.revenue").add(p.get("pnl.cogs")), p.get("pnl.grossProfit"));

        BigDecimal expenses = BigDecimal.ZERO;
        for (JsonNode row : rows) {
            if (row.get("section").asText().equals("expenses") && row.get("level").asInt() == 0) {
                expenses = expenses.add(dec(row.get("amount")));
            }
        }
        money(where + " the expense lines sum to their subtotal", expenses, p.get("pnl.operatingTotal"));
        money(where + " gross profit less expenses is operating profit",
                p.get("pnl.grossProfit").add(p.get("pnl.operatingTotal")), p.get("pnl.profit"));
        money(where + " operating profit is the stored profit", p.get("pnl.profit"),
                dec(stored.get("profit")));
    }

    private void cashFlow(String where, JsonNode rows, JsonNode stored) {
        Money c = new Money(rows);

        BigDecimal operations = c.get("cash.profit").add(c.get("cash.inventory"));
        if (c.has("cash.revaluation")) {
            operations = operations.add(c.get("cash.revaluation"));
        }
        money(where + " operations is profit adjusted for stock", operations, c.get("cash.operatingTotal"));

        BigDecimal investing = c.get("cash.machines");
        if (c.has("cash.refund")) {
            investing = investing.add(c.get("cash.refund"));
        }
        money(where + " investing is machines and refunds", investing, c.get("cash.investingTotal"));
        money(where + " financing ends in its own total", c.get("cash.financing"),
                c.get("cash.financingTotal"));

        money(where + " the three sections add up to the movement",
                c.get("cash.operatingTotal").add(c.get("cash.investingTotal"))
                        .add(c.get("cash.financingTotal")),
                c.get("cash.netChange"));
        money(where + " the movement carries opening to closing",
                c.get("cash.opening").add(c.get("cash.netChange")), c.get("cash.closing"));
        money(where + " closing cash is the stored cash", c.get("cash.closing"), dec(stored.get("cash_after")));
        money(where + " opening cash is the stored opening", c.get("cash.opening"),
                dec(stored.get("opening_cash")));
    }

    private void balanceSheet(String where, JsonNode rows, JsonNode stored) {
        Money b = new Money(rows);

        BigDecimal assets = b.get("balance.cash").add(b.get("balance.inventory"))
                .add(b.get("balance.machines"));
        money(where + " assets are cash, stock and machines", assets, b.get("balance.assetsTotal"));
        money(where + " liabilities end in their own total", b.get("balance.liabilities"),
                b.get("balance.liabilitiesTotal"));

        BigDecimal equity = b.get("balance.capital").add(b.get("balance.retained"));
        if (b.has("balance.refunds")) {
            equity = equity.add(b.get("balance.refunds"));
        }
        if (b.has("balance.revaluation")) {
            equity = equity.add(b.get("balance.revaluation"));
        }
        money(where + " equity is capital and what has been earned", equity, b.get("balance.equityTotal"));

        money(where + " the balance sheet balances", b.get("balance.assetsTotal"),
                b.get("balance.liabilitiesTotal").add(b.get("balance.equityTotal")));
        money(where + " the total line repeats the two sides", b.get("balance.totalLiabilitiesEquity"),
                b.get("balance.assetsTotal"));
        money(where + " the balance sheet's cash is the bank", b.get("balance.cash"),
                dec(stored.get("cash_after")));

        if (b.has("balance.difference")) {
            money(where + " a balanced sheet shows no difference", b.get("balance.difference"),
                    BigDecimal.ZERO);
        }
    }

    /** Money comparisons are to the cent: every figure is already rounded to two decimals. */
    private static void money(String label, BigDecimal actual, BigDecimal expected) {
        assertThat(actual.subtract(expected).abs())
                .as(label + " (" + actual + " vs " + expected + ")")
                .isLessThanOrEqualTo(CENT);
    }

    private static BigDecimal dec(JsonNode node) {
        return node == null || node.isNull() ? BigDecimal.ZERO : node.decimalValue();
    }

    /** The rows of one report, addressed by key, read once per round. */
    private static final class Money {
        private final Map<String, BigDecimal> values = new HashMap<>();

        private Money(JsonNode rows) {
            for (JsonNode row : rows) {
                values.put(row.get("key").asText(), dec(row.get("amount")));
            }
        }

        private BigDecimal get(String key) {
            BigDecimal value = values.get(key);
            assertThat(value).as("the report carries " + key).isNotNull();
            return value;
        }

        private boolean has(String key) {
            return values.containsKey(key);
        }
    }

    private JsonNode financials(String token, long team) throws Exception {
        MvcResult result = mvc.perform(get("/api/runs/" + runId + "/teams/" + team + "/financials")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private Map<Integer, JsonNode> statementsByRound(String token, long team) throws Exception {
        MvcResult result = mvc.perform(get("/api/runs/" + runId + "/teams/" + team + "/statements")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        Map<Integer, JsonNode> byRound = new HashMap<>();
        for (JsonNode row : json.readTree(result.getResponse().getContentAsString())) {
            byRound.put(row.get("round").asInt(), row);
        }
        return new HashMap<>(byRound);
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", "reports", "email", email, "password", "reports-pass-1"))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
