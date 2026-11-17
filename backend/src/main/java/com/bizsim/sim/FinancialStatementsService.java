package com.bizsim.sim;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three reports a review page shows — profit and loss, cash flow, assets and liabilities — for
 * one company, round by round (D88).
 *
 * <p>Every figure comes from rows the engine already stored: {@code round_statement} plus the
 * {@code detail} jsonb written at compute time. Nothing is re-derived from the scenario, so a report
 * always agrees with the round it describes, including rounds computed months ago and rounds of a
 * template whose parameters have since changed.
 *
 * <p>Two identities hold this together, and both are asserted by tests on real computed rounds:
 *
 * <pre>
 *   cash movement = profit − increase in inventories − machines bought + R&amp;D refund
 *   assets        = liabilities + equity
 * </pre>
 *
 * <p>Machines are capitalised: the engine charges {@code capacity_cost} to cash and not to profit
 * (ScoringEngine, D88). They sit at cost with no depreciation, because the model has none; adding
 * depreciation would move profit, prices and every frozen round. R&amp;D is expensed, so the only
 * intangible the company could hold is nothing. That is the model, stated rather than implied.
 *
 * <p>Rows carry an i18n key and a level (0 line, 1 subtotal, 2 total) instead of words: the server
 * has no language, and both the trainee's console and the facilitator's debrief label them
 * themselves.
 */
@Service
public class FinancialStatementsService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal CENT = new BigDecimal("0.01");

    private final RoundService rounds;

    public FinancialStatementsService(RoundService rounds) {
        this.rounds = rounds;
    }

    /**
     * One line of a report. {@code level}: 0 line, 1 subtotal, 2 total. {@code section} groups the
     * lines so the page can rule them together — "operating", "investing", "assets" and so on.
     */
    public record Row(String key, BigDecimal amount, int level, String section) {
    }

    /** The three reports for one round. The balance sheet is as at the end of that round. */
    public record Round(int round, List<Row> pnl, List<Row> cashFlow, List<Row> balance) {
    }

    public record Financials(long teamId, List<Round> rounds) {
    }

    /**
     * Reads the same statements the console reads, with the same access rules — a trainee may ask for
     * their own company only, staff for any company of their run.
     */
    public Financials financials(long tenantId, long userId, String role, long runId, long teamId) {
        List<Map<String, Object>> statements = rounds.statements(tenantId, userId, role, runId, teamId);

        List<Round> out = new ArrayList<>(statements.size());
        BigDecimal inventory = ZERO;
        BigDecimal machinery = ZERO;
        BigDecimal capital = null;
        BigDecimal retained = ZERO;
        BigDecimal refunds = ZERO;
        BigDecimal reserve = ZERO;

        for (Map<String, Object> row : statements) {
            int number = roundNumber(row.get("round"));
            BigDecimal revenue = money(row, "revenue");
            BigDecimal cogs = money(row, "cogs");
            BigDecimal profit = money(row, "profit");
            BigDecimal openingCash = money(row, "opening_cash");
            BigDecimal closingCash = money(row, "cash_after");
            BigDecimal capacity = money(row, "capacity_cost");
            BigDecimal refund = detailMoney(row, "refund");

            // Inventory as the engine valued it at the end of this round. The detail carries the
            // per-product closing values (D79); a statement computed before that fell back to
            // units × unit cost, which is what those rounds charged.
            BigDecimal closingInventory = inventoryOf(row);
            BigDecimal stockChange = closingInventory.subtract(inventory);
            inventory = closingInventory;
            machinery = machinery.add(capacity);
            if (capital == null) {
                // What the owners put in. Round 1 opens with exactly the template's starting cash.
                capital = openingCash;
            }
            retained = retained.add(profit);
            refunds = refunds.add(refund);

            BigDecimal operating = profit.subtract(stockChange);
            BigDecimal investing = refund.subtract(capacity);
            BigDecimal netChange = closingCash.subtract(openingCash);
            // Zero on every weighted-average statement. A pre-D79 replacement-cost statement valued
            // closing stock at a different cost from the units it expensed, and the gap is shown
            // rather than buried. It is a working-capital adjustment, so it belongs to operating cash
            // -- which is why operating is derived from the movement and the investing section, and
            // the three totals add up to the movement exactly.
            BigDecimal revaluation = netChange.subtract(operating).subtract(investing);
            reserve = reserve.add(revaluation);
            operating = operating.add(revaluation);

            List<Row> pnl = new ArrayList<>();
            pnl.add(row("pnl.revenue", revenue, 0, "revenue"));
            pnl.add(row("pnl.cogs", cogs.negate(), 0, "revenue"));
            pnl.add(row("pnl.grossProfit", revenue.subtract(cogs), 1, "revenue"));
            BigDecimal payroll = money(row, "payroll");
            BigDecimal marketing = money(row, "marketing");
            BigDecimal rnd = money(row, "rnd_spend");
            BigDecimal quality = money(row, "quality_cost");
            BigDecimal fixed = money(row, "fixed_cost");
            BigDecimal holding = money(row, "holding_cost");
            BigDecimal hiring = money(row, "hiring_cost");
            BigDecimal report = money(row, "report_cost");
            pnl.add(row("pnl.payroll", payroll.negate(), 0, "expenses"));
            pnl.add(row("pnl.marketing", marketing.negate(), 0, "expenses"));
            pnl.add(row("pnl.rnd", rnd.negate(), 0, "expenses"));
            pnl.add(row("pnl.quality", quality.negate(), 0, "expenses"));
            pnl.add(row("pnl.fixed", fixed.negate(), 0, "expenses"));
            pnl.add(row("pnl.holding", holding.negate(), 0, "expenses"));
            pnl.add(row("pnl.hiring", hiring.negate(), 0, "expenses"));
            pnl.add(row("pnl.report", report.negate(), 0, "expenses"));
            BigDecimal operatingExpenses = payroll.add(marketing).add(rnd).add(quality)
                    .add(fixed).add(holding).add(hiring).add(report);
            pnl.add(row("pnl.operatingTotal", operatingExpenses.negate(), 1, "expenses"));
            pnl.add(row("pnl.profit", profit, 2, "total"));

            List<Row> cashFlow = new ArrayList<>();
            cashFlow.add(row("cash.profit", profit, 0, "operating"));
            cashFlow.add(row("cash.inventory", stockChange.negate(), 0, "operating"));
            if (revaluation.abs().compareTo(CENT) >= 0) {
                cashFlow.add(row("cash.revaluation", revaluation, 0, "operating"));
            }
            cashFlow.add(row("cash.operatingTotal", operating, 1, "operating"));
            cashFlow.add(row("cash.machines", capacity.negate(), 0, "investing"));
            if (refund.signum() != 0) {
                cashFlow.add(row("cash.refund", refund, 0, "investing"));
            }
            cashFlow.add(row("cash.investingTotal", investing, 1, "investing"));
            cashFlow.add(row("cash.financing", ZERO, 0, "financing"));
            cashFlow.add(row("cash.financingTotal", ZERO, 1, "financing"));
            cashFlow.add(row("cash.netChange", netChange, 1, "movement"));
            cashFlow.add(row("cash.opening", openingCash, 0, "movement"));
            cashFlow.add(row("cash.closing", closingCash, 2, "movement"));

            BigDecimal assets = closingCash.add(closingInventory).add(machinery);
            BigDecimal liabilities = ZERO;
            BigDecimal equity = capital.add(retained).add(refunds).add(reserve);

            List<Row> balance = new ArrayList<>();
            balance.add(row("balance.cash", closingCash, 0, "assets"));
            balance.add(row("balance.inventory", closingInventory, 0, "assets"));
            balance.add(row("balance.machines", machinery, 0, "assets"));
            balance.add(row("balance.assetsTotal", assets, 1, "assets"));
            balance.add(row("balance.liabilities", liabilities, 0, "liabilities"));
            balance.add(row("balance.liabilitiesTotal", liabilities, 1, "liabilities"));
            balance.add(row("balance.capital", capital, 0, "equity"));
            balance.add(row("balance.retained", retained, 0, "equity"));
            if (refunds.signum() != 0) {
                balance.add(row("balance.refunds", refunds, 0, "equity"));
            }
            if (reserve.abs().compareTo(CENT) >= 0) {
                balance.add(row("balance.revaluation", reserve, 0, "equity"));
            }
            balance.add(row("balance.equityTotal", equity, 1, "equity"));
            balance.add(row("balance.totalLiabilitiesEquity", liabilities.add(equity), 2, "equity"));
            // Normally exactly zero. Shown when it is not, so a statement that does not balance says
            // so on the page instead of looking tidy.
            balance.add(row("balance.difference",
                    assets.subtract(liabilities).subtract(equity), 0, "check"));

            out.add(new Round(number, pnl, cashFlow, balance));
        }

        return new Financials(teamId, out);
    }

    // ------------------------------------------------------------------ reading stored rows

    /**
     * What the closing stock cost, as the round recorded it. {@code stockValues} arrived with D79;
     * before it a statement carried only units and the unit cost the round charged, which is the same
     * thing under the basis those rounds used.
     */
    private static BigDecimal inventoryOf(Map<String, Object> row) {
        Map<String, Object> detail = detail(row);
        Object values = detail.get("stockValues");
        if (values instanceof Map<?, ?> map && !map.isEmpty()) {
            BigDecimal total = ZERO;
            for (Object value : map.values()) {
                total = total.add(dec(value));
            }
            return total;
        }
        BigDecimal total = ZERO;
        Object products = detail.get("products");
        if (products instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> line) {
                    BigDecimal units = dec(line.get("closingStock"));
                    BigDecimal cost = dec(line.get("unitCost"));
                    total = total.add(units.multiply(cost));
                }
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private static Row row(String key, BigDecimal amount, int level, String section) {
        return new Row(key, amount.setScale(2, RoundingMode.HALF_UP), level, section);
    }

    private static BigDecimal money(Map<String, Object> row, String key) {
        return dec(row.get(key));
    }

    private static BigDecimal detailMoney(Map<String, Object> row, String key) {
        return dec(detail(row).get(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(Map<String, Object> row) {
        Object value = row.get("detail");
        if (value instanceof Map<?, ?> map) {
            // The repository hands back the parsed jsonb object; keys are always strings.
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    private static BigDecimal dec(Object value) {
        if (value == null) {
            return ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = value.toString().trim();
        return text.isEmpty() ? ZERO : new BigDecimal(text);
    }

    private static int roundNumber(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }
}
