package com.bizsim.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.bizsim.web.ProblemException;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The debrief as a file: **every computed round**, each with what the class planned, what it cost and
 * what it earned (D13/D43).
 *
 * <p>Two scopes, one report model. The facilitator gets the whole class; a trainee gets their own
 * company only, without market share or any competitor line (D21). Both read the stored
 * {@code round_result}, {@code round_statement} and {@code decision} rows, never a recomputation, so
 * a downloaded report cannot disagree with the screen.
 *
 * <p>The PDF embeds WenQuanYi Micro Hei: class content is authored and may be Chinese, and a PDF that
 * renders names as boxes is worse than no PDF.
 */
@Service
public class DebriefExportService {

    /** The named costs a statement carries; profit is revenue minus their sum. */
    private static final List<String> COST_KEYS = Labels.COST_KEYS;


    /**
     * The words in the document, in the reader's language (D44). Class content — company, product and
     * trainee names — is authored data and is never translated; only these labels are.
     */
    public record Labels(String runLine, String classReport, String companyReport, String roundWord,
                         String computedWord, String sentBlock, String producedBlock, String costBlock,
                         String company, String trainee, String units, String price, String quality,
                         String marketing, String rnd, String staff, String machines, String review,
                         String bought, String notBought, String noDecision,
                         String roundColumn, String submitted, String yes, String noAnswer,
                         String byTraineeTitle, String byMonthTitle, String totalWord,
                         String roundSummary, String sentByYou, String byTraineeSheet, String roundsWord, String avgShare,
                         String revenue, String cost, String profit, String cumulative, String cash,
                         String share, String brand, String stock, String lostSales, String capacity,
                         String capacityAvailable, String rndCompleted, String status,
                         String resultsSheet, String submissionsSheet, String costSheet,
                         java.util.List<String> costLineNames,
                         /** "en" or "zh": picks the words of the three reports. */
                         String lang) {

        private static final java.util.List<String> COST_KEYS = java.util.List.of(
                "cogs", "payroll", "marketing", "rnd_spend", "quality_cost", "fixed_cost",
                "holding_cost", "hiring_cost", "capacity_cost", "report_cost");

        public static Labels of(String code) {
            return "zh".equalsIgnoreCase(code) ? ZH : EN;
        }

        static final Labels EN = new Labels(
                "Run %d · %s · rounds %d–%d", "class debrief", "company report", "Round %s", "computed",
                "What each company sent", "What the month produced", "Cost lines",
                "Company", "Trainee", "units", "price", "quality", "Marketing", "R&D", "Staff",
                "Machines", "Review", "bought", "no", "no decision submitted",
                "Round", "Submitted", "yes", "no",
                "Summary by trainee", "Comparison by month", "Total",
                "The month's summary", "What you sent", "By trainee", "Rounds", "Avg share %",
                "Revenue", "Cost", "Profit", "Cumulative", "Cash", "Share %", "Brand", "Stock",
                "Lost sales", "Capacity", "Capacity available", "R&D completed", "Status",
                "Results", "Submissions", "Cost lines",
                java.util.List.of("Cost of goods sold", "Payroll", "Marketing", "R&D",
                        "Quality upgrade", "Fixed cost", "Stock holding", "Hiring and firing",
                        "Capacity", "Market review"),
                "en");

        static final Labels ZH = new Labels(
                "模拟 %d · %s · 第 %d–%d 轮", "全班复盘", "公司报告", "第 %s 轮", "结算于",
                "各家提交", "本月结果", "成本明细",
                "公司", "学员", "数量", "单价", "质量", "营销", "研发", "生产人员",
                "设备", "市场报告", "已购买", "未购买", "未提交决策",
                "轮次", "已提交", "是", "否",
                "按学员汇总", "按月度对比", "合计",
                "本月汇总", "你的提交", "按学员", "轮次数", "平均份额",
                "收入", "成本", "利润", "累计利润", "现金", "市场份额", "品牌", "库存",
                "损失销量", "产能", "可用产能", "完成研发数", "状态",
                "结果", "提交", "成本明细",
                java.util.List.of("销货成本", "工资", "营销", "研发",
                        "质量升级", "固定成本", "库存持有", "招聘/解雇",
                        "产能", "市场报告"),
                "zh");

        public String costName(int index) {
            return costLineNames.get(index);
        }
    }

    /**
     * The words of the three reports (D88) inside the exported file. The screen gets them from the
     * console's catalogue; a PDF or a workbook has to carry its own, so this is the same set in the
     * same two languages — a class reading Chinese prints Chinese.
     */
    static final class StatementText {

        final String pnl, cash, balance, block, sheet, statementColumn, sectionColumn, lineColumn,
                levelColumn, amountColumn;
        final Map<String, String> lines, sections;

        private StatementText(String pnl, String cash, String balance, String block, String sheet,
                              String statementColumn, String sectionColumn, String lineColumn,
                              String levelColumn, String amountColumn,
                              Map<String, String> lines, Map<String, String> sections) {
            this.pnl = pnl;
            this.cash = cash;
            this.balance = balance;
            this.block = block;
            this.sheet = sheet;
            this.statementColumn = statementColumn;
            this.sectionColumn = sectionColumn;
            this.lineColumn = lineColumn;
            this.levelColumn = levelColumn;
            this.amountColumn = amountColumn;
            this.lines = lines;
            this.sections = sections;
        }

        static StatementText of(String lang) {
            return "zh".equalsIgnoreCase(lang) ? ZH : EN;
        }

        private static Map<String, String> words(String... pairs) {
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                map.put(pairs[i], pairs[i + 1]);
            }
            return map;
        }

        static final StatementText EN = new StatementText(
                "Profit and loss", "Cash flow statement", "Assets and liabilities",
                "The three statements", "Statements",
                "Statement", "Section", "Line", "Level", "Amount",
                words("pnl.revenue", "Revenue", "pnl.cogs", "Cost of goods sold",
                        "pnl.grossProfit", "Gross profit", "pnl.payroll", "Payroll",
                        "pnl.marketing", "Marketing", "pnl.rnd", "R&D",
                        "pnl.quality", "Quality upgrade", "pnl.fixed", "Fixed cost",
                        "pnl.holding", "Stock holding", "pnl.hiring", "Hiring and firing",
                        "pnl.report", "Market review", "pnl.operatingTotal", "Total operating expenses",
                        "pnl.profit", "Operating profit",
                        "cash.profit", "Profit for the round", "cash.inventory", "Change in inventories",
                        "cash.revaluation", "Inventory revaluation",
                        "cash.operatingTotal", "Net cash from operations",
                        "cash.machines", "Machines bought", "cash.refund", "R&D refund received",
                        "cash.investingTotal", "Net cash used in investing",
                        "cash.financing", "Financing", "cash.financingTotal", "Net cash from financing",
                        "cash.netChange", "Net movement in cash",
                        "cash.opening", "Cash at the start of the round",
                        "cash.closing", "Cash at the end of the round",
                        "balance.cash", "Cash", "balance.inventory", "Inventories",
                        "balance.machines", "Machines at cost", "balance.assetsTotal", "Total assets",
                        "balance.liabilities", "Liabilities", "balance.liabilitiesTotal", "Total liabilities",
                        "balance.capital", "Paid-in capital", "balance.retained", "Retained earnings",
                        "balance.refunds", "R&D refunds received",
                        "balance.revaluation", "Inventory revaluation reserve",
                        "balance.equityTotal", "Total equity",
                        "balance.totalLiabilitiesEquity", "Total liabilities and equity",
                        "balance.difference", "Difference"),
                words("revenue", "Income", "expenses", "Operating expenses",
                        "operating", "Operating activities", "investing", "Investing activities",
                        "financing", "Financing activities", "movement", "Cash movement",
                        "assets", "Assets", "liabilities", "Liabilities", "equity", "Equity"));

        static final StatementText ZH = new StatementText(
                "利润表", "现金流量表", "资产与负债",
                "三张报表", "报表",
                "报表", "分组", "项目", "层级", "金额",
                words("pnl.revenue", "营业收入", "pnl.cogs", "销售成本",
                        "pnl.grossProfit", "毛利", "pnl.payroll", "工资",
                        "pnl.marketing", "市场营销", "pnl.rnd", "研发",
                        "pnl.quality", "质量提升", "pnl.fixed", "固定成本",
                        "pnl.holding", "库存持有成本", "pnl.hiring", "招聘与解雇",
                        "pnl.report", "市场报告", "pnl.operatingTotal", "营业费用合计",
                        "pnl.profit", "营业利润",
                        "cash.profit", "本期利润", "cash.inventory", "存货变动",
                        "cash.revaluation", "存货重估",
                        "cash.operatingTotal", "经营活动现金净额",
                        "cash.machines", "购买机器", "cash.refund", "收到的研发退款",
                        "cash.investingTotal", "投资活动现金净额",
                        "cash.financing", "筹资活动", "cash.financingTotal", "筹资活动现金净额",
                        "cash.netChange", "现金净变动",
                        "cash.opening", "期初现金",
                        "cash.closing", "期末现金",
                        "balance.cash", "现金", "balance.inventory", "存货",
                        "balance.machines", "机器设备（按成本）", "balance.assetsTotal", "资产合计",
                        "balance.liabilities", "负债", "balance.liabilitiesTotal", "负债合计",
                        "balance.capital", "实收资本", "balance.retained", "留存收益",
                        "balance.refunds", "已收研发退款",
                        "balance.revaluation", "存货重估储备",
                        "balance.equityTotal", "所有者权益合计",
                        "balance.totalLiabilitiesEquity", "负债和所有者权益合计",
                        "balance.difference", "差额"),
                words("revenue", "收入", "expenses", "营业费用",
                        "operating", "经营活动", "investing", "投资活动",
                        "financing", "筹资活动", "movement", "现金变动",
                        "assets", "资产", "liabilities", "负债", "equity", "所有者权益"));
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FinancialStatementsService financials;

    public DebriefExportService(JdbcTemplate jdbc, ObjectMapper json,
                                FinancialStatementsService financials) {
        this.jdbc = jdbc;
        this.json = json;
        this.financials = financials;
    }

    public record ProductColumn(String code, String name) {
    }

    /** One company in one round: its plan, its money, its position. */
    public record Row(long teamId, String company, String trainees,
                      Map<String, BigDecimal> units, Map<String, BigDecimal> prices,
                      Map<String, BigDecimal> qualitySpend,
                      BigDecimal marketing, BigDecimal rnd, BigDecimal quality, int staff,
                      BigDecimal machines, boolean review, boolean planSent,
                      BigDecimal revenue, BigDecimal cost, BigDecimal profit,
                      BigDecimal cumulativeProfit, BigDecimal cash, BigDecimal share,
                      BigDecimal brand, BigDecimal stock, BigDecimal lostSales,
                      BigDecimal capacityUsed, BigDecimal capacityAvailable, int rndCompleted,
                      String status, Map<String, BigDecimal> costLines,
                      /** D88/D13: this round's three statements, from the service the screen reads. */
                      FinancialStatementsService.Round statements) {
    }

    public record RoundReport(int round, Instant computedAt, List<Row> rows) {
    }

    /**
     * @param classScope true for the facilitator's report; false for one company's own report, which
     *                   drops market share and the class totals.
     */
    public record Report(long runId, String title, String scenarioKey, boolean classScope,
                         List<ProductColumn> products, List<RoundReport> rounds) {
    }

    // ------------------------------------------------------------------ loading

    public Report load(long tenantId, long runId, Integer requestedRound, Long teamFilter) {
        List<Map<String, Object>> meta = jdbc.query("""
                select s.name, r.scenario_key
                from run r join simulation s on s.id = r.simulation_id
                where r.id = ? and r.tenant_id = ?
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", rs.getString("name"));
            row.put("scenarioKey", rs.getString("scenario_key"));
            return row;
        }, runId, tenantId);
        if (meta.isEmpty()) {
            throw ProblemException.notFound("run.not_found", "no such run");
        }
        String scenarioKey = (String) meta.get(0).get("scenarioKey");
        // D77: `capacityPurchase` is money; the sheet shows machines, so it divides by this.
        BigDecimal machineCost = jdbc.queryForObject(
                "select (definition->>'machineCostPerWorker')::numeric from scenario where key = ?",
                BigDecimal.class, scenarioKey);

        List<ProductColumn> catalogue = jdbc.query(
                "select code, name from product where scenario_key = ? order by code",
                (rs, i) -> new ProductColumn(rs.getString("code"), rs.getString("name")), scenarioKey);

        // D89: the sheets list the products the companies in this report actually carry, not the whole
        // catalogue. A trainee who trades two products gets two product columns, not three with a zero.
        List<String> carried = jdbc.queryForList("""
                select distinct cp.product_code
                from company_product cp join team t on t.id = cp.team_id
                where t.run_id = ? and (?::bigint is null or t.id = ?::bigint)
                """, String.class, runId, teamFilter, teamFilter);
        List<ProductColumn> products = new ArrayList<>();
        for (ProductColumn product : catalogue) {
            if (carried.isEmpty() || carried.contains(product.code())) {
                products.add(product);
            }
        }

        List<Map<String, Object>> rows = jdbc.query("""
                select r.number as round_number, rr.computed_at,
                       t.id as team_id, t.company_name,
                       coalesce((select string_agg(u.display_name, ', ' order by u.display_name)
                                 from player p join app_user u on u.id = p.user_id
                                 where p.team_id = t.id), '') as trainees,
                       (rr.score->>'revenue')::numeric             as revenue,
                       (rr.score->>'marketShare')::numeric         as market_share,
                       (rr.score->>'cumulativeProfit')::numeric    as cumulative_profit,
                       (rr.score->>'cash')::numeric                as cash,
                       (rr.score->>'brand')::numeric               as brand,
                       (rr.score->>'stock')::numeric               as stock,
                       (rr.score->>'lostSales')::numeric           as lost_sales,
                       (rr.score->>'rndCompleted')::int            as rnd_completed,
                       rr.score->>'status'                         as status,
                       s.cogs, s.payroll, s.marketing, s.rnd_spend, s.quality_cost, s.fixed_cost,
                       s.holding_cost, s.hiring_cost, s.capacity_cost, s.report_cost,
                       s.capacity_used, s.capacity_available,
                       coalesce(s.profit, (rr.score->>'profit')::numeric) as profit,
                       d.payload::text as payload
                from round r
                join round_result rr on rr.round_id = r.id
                join team t on t.id = rr.team_id
                left join round_statement s on s.round_id = r.id and s.team_id = t.id
                left join decision d on d.round_id = r.id and d.team_id = t.id
                where r.run_id = ? and r.status = 'COMPUTED'
                  and (?::int is null or r.number = ?::int)
                  and (?::bigint is null or t.id = ?::bigint)
                order by r.number, rr.total_score desc, t.id
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("round", rs.getInt("round_number"));
            row.put("computedAt", rs.getTimestamp("computed_at") == null ? null
                    : rs.getTimestamp("computed_at").toInstant());
            row.put("teamId", rs.getLong("team_id"));
            row.put("company", rs.getString("company_name"));
            row.put("trainees", rs.getString("trainees"));
            row.put("revenue", nz(rs.getBigDecimal("revenue")));
            row.put("share", nz(rs.getBigDecimal("market_share")));
            row.put("cumulativeProfit", nz(rs.getBigDecimal("cumulative_profit")));
            row.put("cash", nz(rs.getBigDecimal("cash")));
            row.put("brand", nz(rs.getBigDecimal("brand")));
            row.put("stock", nz(rs.getBigDecimal("stock")));
            row.put("lostSales", nz(rs.getBigDecimal("lost_sales")));
            row.put("rndCompleted", rs.getInt("rnd_completed"));
            row.put("status", rs.getString("status"));
            row.put("profit", nz(rs.getBigDecimal("profit")));
            row.put("capacityUsed", nz(rs.getBigDecimal("capacity_used")));
            row.put("capacityAvailable", nz(rs.getBigDecimal("capacity_available")));
            Map<String, BigDecimal> costs = new LinkedHashMap<>();
            for (String key : COST_KEYS) {
                costs.put(key, nz(rs.getBigDecimal(key)));
            }
            row.put("costLines", costs);
            row.put("payload", rs.getString("payload"));
            return row;
        }, runId, requestedRound, requestedRound, teamFilter, teamFilter);

        if (rows.isEmpty()) {
            throw ProblemException.conflict("round.none_computed", "no round has been computed yet");
        }

        Map<Integer, List<Row>> byRound = new LinkedHashMap<>();
        Map<Integer, Instant> computedAt = new LinkedHashMap<>();
        // D88/D13: the three statements travel with the file, computed by the service the screen
        // reads — one load per company, then looked up by round.
        Map<Long, Map<Integer, FinancialStatementsService.Round>> books = new LinkedHashMap<>();
        for (Map<String, Object> raw : rows) {
            long teamId = (Long) raw.get("teamId");
            books.computeIfAbsent(teamId, id -> byRound(financials.forTeam(runId, id)));
        }
        for (Map<String, Object> raw : rows) {
            int round = (Integer) raw.get("round");
            computedAt.putIfAbsent(round, (Instant) raw.get("computedAt"));
            byRound.computeIfAbsent(round, key -> new ArrayList<>()).add(
                    toRow(raw, products, machineCost,
                            books.getOrDefault((Long) raw.get("teamId"), Map.of()).get(round)));
        }

        List<RoundReport> rounds = new ArrayList<>();
        for (Map.Entry<Integer, List<Row>> entry : byRound.entrySet()) {
            rounds.add(new RoundReport(entry.getKey(), computedAt.get(entry.getKey()), entry.getValue()));
        }

        String title = (String) meta.get(0).get("name");
        if (teamFilter != null) {
            title = rounds.get(rounds.size() - 1).rows().get(0).company() + " — " + title;
        }
        return new Report(runId, title, scenarioKey, teamFilter == null, products, rounds);
    }

    private static Map<Integer, FinancialStatementsService.Round> byRound(
            FinancialStatementsService.Financials books) {
        Map<Integer, FinancialStatementsService.Round> map = new LinkedHashMap<>();
        for (FinancialStatementsService.Round round : books.rounds()) {
            map.put(round.round(), round);
        }
        return map;
    }

    private Row toRow(Map<String, Object> raw, List<ProductColumn> products, BigDecimal machineCost,
                      FinancialStatementsService.Round statements) {
        DecisionPayload decision = parse((String) raw.get("payload"));
        Map<String, BigDecimal> units = new LinkedHashMap<>();
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        Map<String, BigDecimal> qualitySpend = new LinkedHashMap<>();
        for (ProductColumn product : products) {
            units.put(product.code(), decision == null ? BigDecimal.ZERO : decision.units(product.code()));
            prices.put(product.code(), decision == null ? BigDecimal.ZERO
                    : nz(decision.priceOf(product.code())));
            qualitySpend.put(product.code(), decision == null ? BigDecimal.ZERO
                    : decision.qualityInvest(product.code()));
        }
        BigDecimal marketing = decision == null ? BigDecimal.ZERO : decision.marketingTotal();
        BigDecimal rnd = decision == null ? BigDecimal.ZERO : decision.rndTotal();
        BigDecimal quality = decision == null ? BigDecimal.ZERO : decision.qualityTotal();
        int staff = decision == null ? 0 : decision.productionTarget().orElse(0);
        BigDecimal machines = decision == null ? BigDecimal.ZERO : decision.capacityPurchaseOrZero();
        // D77: the stored figure is money — a column headed "machines" must show a count.
        if (machineCost != null && machineCost.signum() > 0) {
            machines = machines.divide(machineCost, 0, java.math.RoundingMode.HALF_UP);
        }
        boolean review = decision != null && decision.marketReportRequested();

        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> costs = (Map<String, BigDecimal>) raw.get("costLines");
        BigDecimal cost = costs.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        return new Row((Long) raw.get("teamId"), (String) raw.get("company"), (String) raw.get("trainees"),
                units, prices, qualitySpend, marketing, rnd, quality, staff, machines, review,
                decision != null, (BigDecimal) raw.get("revenue"), cost, (BigDecimal) raw.get("profit"),
                (BigDecimal) raw.get("cumulativeProfit"), (BigDecimal) raw.get("cash"),
                (BigDecimal) raw.get("share"), (BigDecimal) raw.get("brand"),
                (BigDecimal) raw.get("stock"), (BigDecimal) raw.get("lostSales"),
                (BigDecimal) raw.get("capacityUsed"), (BigDecimal) raw.get("capacityAvailable"),
                (Integer) raw.get("rndCompleted"), (String) raw.get("status"), costs, statements);
    }

    private DecisionPayload parse(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            return json.readValue(payload, DecisionPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("stored decision is unreadable", e);
        }
    }

    // ------------------------------------------------------------------ PDF

    private record Fonts(Font title, Font subtitle, Font roundHead, Font blockHead, Font statementHead,
                         Font head, Font body, Font boldBody) {
    }

    /**
     * The document, structured by who reads it (D46).
     *
     * <p><b>A trainee</b> gets one page per round: the month's summary, what they sent, and the cost
     * lines — a term that can be read one month at a time.
     *
     * <p><b>The facilitator</b> gets two summaries before the detail: <b>by trainee</b> (one page per
     * company, its rounds in sequence) and <b>by month</b> (one page per round, every company side by
     * side) — the two ways a class reviews a term.
     */
    public byte[] pdf(Report report, Labels labels) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 30, 30, 28, 28);
            PdfWriter.getInstance(document, out);
            document.open();

            BaseFont face = embedded();
            Fonts fonts = new Fonts(new Font(face, 18), new Font(face, 9), new Font(face, 14),
                    new Font(face, 10), new Font(face, 12, Font.BOLD),
                    new Font(face, 8), new Font(face, 8), new Font(face, 8, Font.BOLD));

            document.add(new Paragraph(report.title(), fonts.title()));
            document.add(new Paragraph(String.format(labels.runLine(), report.runId(),
                    report.scenarioKey(), report.rounds().get(0).round(),
                    report.rounds().get(report.rounds().size() - 1).round()), fonts.subtitle()));
            document.add(new Paragraph(report.classScope() ? labels.classReport()
                    : labels.companyReport(), fonts.subtitle()));

            // The title shares the first page with the first section; nothing is spent on a cover.
            boolean[] first = {true};
            if (report.classScope()) {
                byTraineePages(document, report, labels, fonts, first);
                for (RoundReport round : report.rounds()) {
                    monthPage(document, report, round, labels, fonts, first);
                }
            } else {
                for (RoundReport round : report.rounds()) {
                    traineePage(document, report, round, labels, fonts, first);
                }
            }

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the debrief PDF", e);
        }
    }

    /** One page per company: its rounds in sequence, then the term's totals. */
    private record Dated(int round, Row row) {
    }

    private void byTraineePages(Document document, Report report, Labels labels, Fonts fonts,
                                boolean[] first) {
        Map<Long, List<Dated>> byTeam = new LinkedHashMap<>();
        for (RoundReport round : report.rounds()) {
            for (Row row : round.rows()) {
                byTeam.computeIfAbsent(row.teamId(), key -> new ArrayList<>())
                        .add(new Dated(round.round(), row));
            }
        }
        for (List<Dated> rows : byTeam.values()) {
            Row head = rows.get(0).row();
            pageHead(document, head.company()
                            + (head.trainees().isEmpty() ? "" : " · " + head.trainees()),
                    labels.byTraineeTitle(), fonts, first);
            document.add(new Paragraph(" ", fonts.subtitle()));
            document.add(traineeRoundTable(rows, labels, fonts));
        }
    }

    /** One page per round: every company on the same lines, then the detail behind those numbers. */
    private void monthPage(Document document, Report report, RoundReport round, Labels labels,
                           Fonts fonts, boolean[] first) {
        pageHead(document, String.format(labels.roundWord(), round.round()), "", fonts, first);
        document.add(new Paragraph(labels.byMonthTitle(), fonts.blockHead()));
        document.add(resultTable(report, round, labels, fonts.head, fonts.body));
        document.add(new Paragraph(labels.sentBlock(), fonts.blockHead()));
        document.add(submissionTable(report, round, labels, fonts.head, fonts.body));
        document.add(new Paragraph(labels.costBlock(), fonts.blockHead()));
        document.add(costTable(round, labels, fonts.head, fonts.body));
    }

    /** One page per round for one company: the summary first, then what produced it. */
    private void traineePage(Document document, Report report, RoundReport round, Labels labels,
                             Fonts fonts, boolean[] first) {
        pageHead(document, String.format(labels.roundWord(), round.round()), "", fonts, first);
        document.add(new Paragraph(labels.roundSummary(), fonts.blockHead()));
        document.add(resultTable(report, round, labels, fonts.head, fonts.body));
        document.add(new Paragraph(labels.sentByYou(), fonts.blockHead()));
        document.add(submissionTable(report, round, labels, fonts.head, fonts.body));
        document.add(new Paragraph(labels.costBlock(), fonts.blockHead()));
        document.add(costTable(round, labels, fonts.head, fonts.body));
        statementsBlock(document, round.rows().get(0), labels, fonts);
    }

    /**
     * D88 in print: the three statements for one company and one round, read down one column, ruled
     * the way the console rules them. Company reports only — the class debrief is a projection
     * document, and its statements are on the screen.
     */
    private void statementsBlock(Document document, Row row, Labels labels, Fonts fonts) {
        FinancialStatementsService.Round statements = row.statements();
        if (statements == null) {
            return;
        }
        StatementText text = StatementText.of(labels.lang());
        document.add(new Paragraph(text.block, fonts.blockHead()));
        // D88: each statement carries its own name inside its table, so the title can never be
        // orphaned from the figures it introduces when the block breaks across a page.
        document.add(statementPdf(statements.pnl(), text, fonts, text.pnl));
        document.add(statementPdf(statements.cashFlow(), text, fonts, text.cash));
        balanceTables(document, statements.balance(), text, fonts, text.balance);
    }

    /** One statement as a two-column table: its title, its sections, then the figures. */
    private PdfPTable statementPdf(List<FinancialStatementsService.Row> rows, StatementText text,
                                   Fonts fonts, String title) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{4.6f, 1.5f});
        table.setSpacingBefore(2);
        table.setSpacingAfter(5);
        table.setHeaderRows(0);
        // A statement is read as one block; a page break inside it would split a subtotal from the
        // lines it summarises.
        table.setKeepTogether(true);

        if (title != null) {
            PdfPCell head = new PdfPCell(new Phrase(title, fonts.statementHead()));
            head.setColspan(2);
            head.setBorder(Rectangle.NO_BORDER);
            head.setPaddingLeft(0);
            head.setPaddingBottom(2);
            table.addCell(head);
        }

        String section = null;
        for (FinancialStatementsService.Row line : rows) {
            if (!line.section().equals(section)) {
                section = line.section();
                String name = text.sections.get(section);
                if (name != null) {
                    PdfPCell head = new PdfPCell(new Phrase(name, fonts.head()));
                    head.setColspan(2);
                    head.setBorder(Rectangle.NO_BORDER);
                    head.setPaddingTop(6);
                    head.setPaddingLeft(0);
                    table.addCell(head);
                }
            }
            Font font = line.level() >= 1 ? fonts.boldBody() : fonts.body();
            int border = line.level() >= 2 ? Rectangle.TOP | Rectangle.BOTTOM
                    : line.level() == 1 ? Rectangle.TOP : Rectangle.NO_BORDER;
            PdfPCell label = new PdfPCell(new Phrase(
                    text.lines.getOrDefault(line.key(), line.key()), font));
            label.setBorder(border);
            label.setPaddingTop(3);
            label.setPaddingBottom(3);
            label.setPaddingLeft(0);
            label.setPaddingRight(6);
            PdfPCell amount = new PdfPCell(new Phrase(grouped(line.amount()), font));
            amount.setBorder(border);
            amount.setHorizontalAlignment(Element.ALIGN_RIGHT);
            amount.setPaddingTop(3);
            amount.setPaddingBottom(3);
            table.addCell(label);
            table.addCell(amount);
        }
        return table;
    }

    /** Assets against liabilities and equity, the two totals level with each other. */
    private void balanceTables(Document document, List<FinancialStatementsService.Row> rows,
                               StatementText text, Fonts fonts, String title) {
        List<FinancialStatementsService.Row> assets = new ArrayList<>();
        List<FinancialStatementsService.Row> other = new ArrayList<>();
        List<FinancialStatementsService.Row> difference = new ArrayList<>();
        for (FinancialStatementsService.Row line : rows) {
            if ("assets".equals(line.section())) {
                assets.add(line);
            } else if ("check".equals(line.section())) {
                if (line.amount().abs().compareTo(new BigDecimal("1")) >= 0) {
                    difference.add(line);
                }
            } else {
                other.add(line);
            }
        }

        PdfPTable outer = new PdfPTable(2);
        outer.setWidthPercentage(100);
        outer.setWidths(new float[]{1f, 1f});
        outer.setSpacingBefore(2);
        outer.setSpacingAfter(5);
        outer.setKeepTogether(true);
        if (title != null) {
            PdfPCell head = new PdfPCell(new Phrase(title, fonts.statementHead()));
            head.setColspan(2);
            head.setBorder(Rectangle.NO_BORDER);
            head.setPaddingLeft(0);
            head.setPaddingBottom(2);
            outer.addCell(head);
        }
        for (List<FinancialStatementsService.Row> side : List.of(assets, other)) {
            PdfPCell cell = new PdfPCell(statementPdf(side, text, fonts, null));
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(0);
            cell.setPaddingRight(10);
            outer.addCell(cell);
        }
        document.add(outer);
        if (!difference.isEmpty()) {
            document.add(statementPdf(difference, text, fonts, null));
        }
    }

    private void pageHead(Document document, String heading, String subheading, Fonts fonts,
                          boolean[] first) {
        if (!first[0]) {
            document.newPage();
        }
        first[0] = false;
        document.add(new Paragraph(heading, fonts.roundHead()));
        if (subheading != null && !subheading.isEmpty()) {
            document.add(new Paragraph(subheading, fonts.subtitle()));
        }
    }

    /** A company's rounds, one line each, with the term's totals underneath. */
    private PdfPTable traineeRoundTable(List<Dated> rows, Labels labels, Fonts fonts) {
        List<String> columns = new ArrayList<>(List.of(labels.roundColumn(), labels.revenue(),
                labels.cost(), labels.profit(), labels.cumulative(), labels.cash(), labels.share(),
                labels.brand(), labels.stock(), labels.lostSales(), labels.capacity()));
        PdfPTable table = new PdfPTable(columns.size());
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        List<Float> widths = new ArrayList<>(List.of(1.1f));
        for (int i = 1; i < columns.size(); i++) {
            widths.add(2.15f);
        }
        setWidths(table, widths);
        header(table, fonts.head, columns.toArray(new String[0]));

        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal profit = BigDecimal.ZERO;
        BigDecimal lost = BigDecimal.ZERO;
        Row last = rows.get(rows.size() - 1).row();
        for (Dated dated : rows) {
            Row row = dated.row();
            cell(table, fonts.body, String.valueOf(dated.round()));
            money(table, fonts.body, row.revenue());
            money(table, fonts.body, row.cost());
            money(table, fonts.body, row.profit());
            money(table, fonts.body, row.cumulativeProfit());
            money(table, fonts.body, row.cash());
            cell(table, fonts.body, plain(row.share()));
            cell(table, fonts.body, plain(row.brand()));
            cell(table, fonts.body, plain(row.stock()));
            cell(table, fonts.body, plain(row.lostSales()));
            cell(table, fonts.body, plain(row.capacityUsed()) + " / " + plain(row.capacityAvailable()));
            revenue = revenue.add(row.revenue());
            cost = cost.add(row.cost());
            profit = profit.add(row.profit());
            lost = lost.add(row.lostSales());
        }
        cell(table, fonts.body, labels.totalWord());
        money(table, fonts.body, revenue);
        money(table, fonts.body, cost);
        money(table, fonts.body, profit);
        money(table, fonts.body, last.cumulativeProfit());
        money(table, fonts.body, last.cash());
        cell(table, fonts.body, plain(last.share()));
        cell(table, fonts.body, plain(last.brand()));
        cell(table, fonts.body, plain(last.stock()));
        cell(table, fonts.body, plain(lost));
        cell(table, fonts.body, plain(last.capacityUsed()) + " / " + plain(last.capacityAvailable()));
        return table;
    }

    /** One row per company: per-product plan, then the money the plan commits. */
    private PdfPTable submissionTable(Report report, RoundReport round, Labels labels, Font head,
                                      Font body) {
        int productColumns = report.products().size() * 3;
        PdfPTable table = new PdfPTable(2 + productColumns + 5);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        // Content decides the widths: names need room, numbers need just enough (D45).
        List<Float> widths = new ArrayList<>(List.of(2.6f, 1.9f));
        for (int i = 0; i < report.products().size(); i++) {
            widths.add(1.0f);
            widths.add(1.6f);
            widths.add(0.95f);
        }
        widths.addAll(List.of(1.8f, 1.4f, 0.9f, 1.5f, 1.0f));
        setWidths(table, widths);
        table.setHeaderRows(1);

        List<String> columns = new ArrayList<>(List.of(labels.company(), labels.trainee()));
        for (ProductColumn product : report.products()) {
            columns.add(product.code() + " " + labels.units());
            columns.add(product.code() + " " + labels.price());
            columns.add(product.code() + " " + labels.quality());
        }
        columns.addAll(List.of(labels.marketing(), labels.rnd(), labels.staff(), labels.machines(),
                labels.review()));
        header(table, head, columns.toArray(new String[0]));

        for (Row row : round.rows()) {
            cell(table, body, row.company());
            cell(table, body, row.trainees());
            for (ProductColumn product : report.products()) {
                cell(table, body, row.planSent() ? plain(row.units().get(product.code())) : "—");
                cell(table, body, row.planSent() ? grouped(row.prices().get(product.code())) : "—");
                cell(table, body, row.planSent() ? grouped(row.qualitySpend().get(product.code())) : "—");
            }
            if (!row.planSent()) {
                cell(table, body, labels.noDecision());
                for (int i = 0; i < 4; i++) {
                    cell(table, body, "—");
                }
            } else {
                money(table, body, row.marketing());
                money(table, body, row.rnd());
                cell(table, body, String.valueOf(row.staff()));
                money(table, body, row.machines());
                cell(table, body, row.review() ? labels.bought() : labels.notBought());
            }
        }
        return table;
    }

    private PdfPTable resultTable(Report report, RoundReport round, Labels labels, Font head, Font body) {
        int columns = report.classScope() ? 12 : 11;
        PdfPTable table = new PdfPTable(columns);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        // Twelve columns only fit a landscape page when each one is sized for what it holds: the
        // company and trainee columns take the space, the numbers take what a figure needs.
        List<Float> widths = new ArrayList<>(List.of(2.9f, 1.9f, 2.15f, 2.15f, 2.0f, 2.25f, 2.15f));
        if (report.classScope()) {
            widths.add(1.05f);
        }
        widths.addAll(List.of(0.95f, 0.95f, 1.25f, 1.7f));
        setWidths(table, widths);
        table.setHeaderRows(1);

        List<String> headers = new ArrayList<>(List.of(labels.company(), labels.trainee(),
                labels.revenue(), labels.cost(), labels.profit(), labels.cumulative(), labels.cash()));
        if (report.classScope()) {
            headers.add(labels.share());
        }
        headers.addAll(List.of(labels.brand(), labels.stock(), labels.lostSales(), labels.capacity()));
        header(table, head, headers.toArray(new String[0]));

        for (Row row : round.rows()) {
            cell(table, body, row.company());
            cell(table, body, row.trainees());
            money(table, body, row.revenue());
            money(table, body, row.cost());
            money(table, body, row.profit());
            money(table, body, row.cumulativeProfit());
            money(table, body, row.cash());
            if (report.classScope()) {
                cell(table, body, plain(row.share()));
            }
            cell(table, body, plain(row.brand()));
            cell(table, body, plain(row.stock()));
            cell(table, body, plain(row.lostSales()));
            cell(table, body, plain(row.capacityUsed()) + " / " + plain(row.capacityAvailable()));
        }
        return table;
    }

    private PdfPTable costTable(RoundReport round, Labels labels, Font head, Font body) {
        PdfPTable table = new PdfPTable(COST_KEYS.size() + 2);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        List<Float> widths = new ArrayList<>(List.of(2.9f, 1.9f));
        for (int i = 0; i < COST_KEYS.size(); i++) {
            widths.add(2.0f);
        }
        setWidths(table, widths);
        table.setHeaderRows(1);
        List<String> columns = new ArrayList<>(List.of(labels.company(), labels.trainee()));
        for (int i = 0; i < COST_KEYS.size(); i++) {
            columns.add(labels.costName(i));
        }
        header(table, head, columns.toArray(new String[0]));
        for (Row row : round.rows()) {
            cell(table, body, row.company());
            cell(table, body, row.trainees());
            for (String key : COST_KEYS) {
                money(table, body, row.costLines().getOrDefault(key, BigDecimal.ZERO));
            }
        }
        return table;
    }

    private static BaseFont embedded() throws Exception {
        try (InputStream in = new ClassPathResource("fonts/wqy-microhei.ttc").getInputStream()) {
            return BaseFont.createFont("wqy-microhei.ttc,0", BaseFont.IDENTITY_H,
                    BaseFont.EMBEDDED, true, in.readAllBytes(), null);
        }
    }

    /** OpenPDF wants a float[], and the ratios are easier to read as a list of weights. */
    private static void setWidths(PdfPTable table, List<Float> widths) {
        float[] values = new float[widths.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = widths.get(i);
        }
        table.setWidths(values);
    }

    private static void header(PdfPTable table, Font font, String... labels) {
        for (String label : labels) {
            PdfPCell cell = new PdfPCell(new Phrase(label, font));
            cell.setBackgroundColor(new java.awt.Color(0xE8, 0xEE, 0xF6));
            cell.setPadding(3);
            table.addCell(cell);
        }
    }

    private static void cell(PdfPTable table, Font font, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
        cell.setPadding(3);
        table.addCell(cell);
    }

    private static void money(PdfPTable table, Font font, BigDecimal value) {
        PdfPCell cell = new PdfPCell(new Phrase(grouped(value), font));
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cell.setNoWrap(true);
        cell.setPadding(3);
        table.addCell(cell);
    }

    // ------------------------------------------------------------------ XLSX

    public byte[] xlsx(Report report, Labels labels) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle head = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            head.setFont(boldFont);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            if (report.classScope()) {
                byTraineeSheet(workbook, report, labels, head);
            }
            summarySheet(workbook, report, labels, head);
            submissionSheet(workbook, report, labels, head);
            costSheet(workbook, report, labels, head);
            statementsSheet(workbook, report, labels, head);

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the debrief workbook", e);
        }
    }

    /** One row per company over the whole term: the workbook's answer to "how did each trainee do?". */
    private void byTraineeSheet(Workbook workbook, Report report, Labels labels, CellStyle head) {
        Sheet sheet = workbook.createSheet(labels.byTraineeSheet());
        List<String> columns = new ArrayList<>(List.of(labels.company(), labels.trainee(),
                labels.roundsWord(), labels.revenue(), labels.cost(), labels.profit(),
                labels.cumulative(), labels.cash(), labels.avgShare(), labels.brand(), labels.stock(),
                labels.lostSales()));
        writeHeader(sheet, head, columns.toArray(new String[0]));

        Map<Long, List<Row>> byTeam = new LinkedHashMap<>();
        for (RoundReport round : report.rounds()) {
            for (Row row : round.rows()) {
                byTeam.computeIfAbsent(row.teamId(), key -> new ArrayList<>()).add(row);
            }
        }
        int index = 1;
        for (List<Row> rows : byTeam.values()) {
            Row last = rows.get(rows.size() - 1);
            org.apache.poi.ss.usermodel.Row line = sheet.createRow(index++);
            int c = 0;
            line.createCell(c++).setCellValue(last.company());
            line.createCell(c++).setCellValue(last.trainees());
            line.createCell(c++).setCellValue(rows.size());
            c = number(line, c, sum(rows, Row::revenue));
            c = number(line, c, sum(rows, Row::cost));
            c = number(line, c, sum(rows, Row::profit));
            c = number(line, c, last.cumulativeProfit());
            c = number(line, c, last.cash());
            c = number(line, c, average(rows, Row::share));
            c = number(line, c, last.brand());
            c = number(line, c, last.stock());
            c = number(line, c, sum(rows, Row::lostSales));
        }
        sizes(sheet, columns.size());
        sheet.setColumnWidth(0, 26 * 256);
        sheet.setColumnWidth(1, 26 * 256);
    }

    private static BigDecimal sum(List<Row> rows, java.util.function.Function<Row, BigDecimal> field) {
        return rows.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal average(List<Row> rows,
                                      java.util.function.Function<Row, BigDecimal> field) {
        if (rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return sum(rows, field).divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP);
    }

    private void summarySheet(Workbook workbook, Report report, Labels labels, CellStyle head) {
        Sheet sheet = workbook.createSheet(labels.resultsSheet());
        List<String> columns = new ArrayList<>(List.of(labels.roundColumn(),
                labels.company(), labels.trainee(), labels.revenue(), labels.cost(), labels.profit(),
                labels.cumulative(), labels.cash()));
        if (report.classScope()) {
            columns.add(labels.share());
        }
        columns.addAll(List.of(labels.brand(), labels.stock(), labels.lostSales(), labels.capacity(),
                labels.capacityAvailable(), labels.rndCompleted(), labels.status()));
        writeHeader(sheet, head, columns.toArray(new String[0]));

        int index = 1;
        for (RoundReport round : report.rounds()) {
            for (Row row : round.rows()) {
                org.apache.poi.ss.usermodel.Row line = sheet.createRow(index++);
                int c = 0;
                line.createCell(c++).setCellValue(round.round());
                line.createCell(c++).setCellValue(row.company());
                line.createCell(c++).setCellValue(row.trainees());
                c = number(line, c, row.revenue());
                c = number(line, c, row.cost());
                c = number(line, c, row.profit());
                c = number(line, c, row.cumulativeProfit());
                c = number(line, c, row.cash());
                if (report.classScope()) {
                    c = number(line, c, row.share());
                }
                c = number(line, c, row.brand());
                c = number(line, c, row.stock());
                c = number(line, c, row.lostSales());
                c = number(line, c, row.capacityUsed());
                c = number(line, c, row.capacityAvailable());
                line.createCell(c++).setCellValue(row.rndCompleted());
                line.createCell(c).setCellValue(row.status());
            }
        }
        sizes(sheet, columns.size());
        sheet.setColumnWidth(1, 26 * 256);
        sheet.setColumnWidth(2, 26 * 256);
    }

    private void submissionSheet(Workbook workbook, Report report, Labels labels, CellStyle head) {
        Sheet sheet = workbook.createSheet(labels.submissionsSheet());
        List<String> columns = new ArrayList<>(List.of(labels.roundColumn(),
                labels.company(), labels.trainee()));
        for (ProductColumn product : report.products()) {
            columns.add(product.code() + " " + labels.units());
            columns.add(product.code() + " " + labels.price());
            columns.add(product.code() + " " + labels.quality());
        }
        columns.addAll(List.of(labels.marketing(), labels.rnd(), labels.quality(), labels.staff(),
                labels.machines(), labels.review(), labels.submitted()));
        writeHeader(sheet, head, columns.toArray(new String[0]));

        int index = 1;
        for (RoundReport round : report.rounds()) {
            for (Row row : round.rows()) {
                org.apache.poi.ss.usermodel.Row line = sheet.createRow(index++);
                int c = 0;
                line.createCell(c++).setCellValue(round.round());
                line.createCell(c++).setCellValue(row.company());
                line.createCell(c++).setCellValue(row.trainees());
                for (ProductColumn product : report.products()) {
                    c = number(line, c, row.units().get(product.code()));
                    c = number(line, c, row.prices().get(product.code()));
                    c = number(line, c, row.qualitySpend().get(product.code()));
                }
                c = number(line, c, row.marketing());
                c = number(line, c, row.rnd());
                c = number(line, c, row.quality());
                line.createCell(c++).setCellValue(row.staff());
                c = number(line, c, row.machines());
                line.createCell(c++).setCellValue(row.review() ? labels.bought() : labels.notBought());
                line.createCell(c).setCellValue(row.planSent() ? labels.yes() : labels.noAnswer());
            }
        }
        sizes(sheet, columns.size());
        sheet.setColumnWidth(1, 26 * 256);
        sheet.setColumnWidth(2, 26 * 256);
    }

    private void costSheet(Workbook workbook, Report report, Labels labels, CellStyle head) {
        Sheet sheet = workbook.createSheet(labels.costSheet());
        List<String> columns = new ArrayList<>(List.of(labels.roundColumn(),
                labels.company()));
        for (int i = 0; i < COST_KEYS.size(); i++) {
            columns.add(labels.costName(i));
        }
        writeHeader(sheet, head, columns.toArray(new String[0]));

        int index = 1;
        for (RoundReport round : report.rounds()) {
            for (Row row : round.rows()) {
                org.apache.poi.ss.usermodel.Row line = sheet.createRow(index++);
                int c = 0;
                line.createCell(c++).setCellValue(round.round());
                line.createCell(c++).setCellValue(row.company());
                for (String key : COST_KEYS) {
                    c = number(line, c, row.costLines().getOrDefault(key, BigDecimal.ZERO));
                }
            }
        }
        sizes(sheet, columns.size());
        sheet.setColumnWidth(1, 26 * 256);
    }

    /**
     * D88 in the workbook: every line of every statement, one row each, with the statement, the block
     * and the level beside it so the sheet can be filtered or pivoted like the others. Same numbers as
     * the console, from the same service.
     */
    private void statementsSheet(Workbook workbook, Report report, Labels labels, CellStyle head) {
        StatementText text = StatementText.of(labels.lang());
        Sheet sheet = workbook.createSheet(text.sheet);
        List<String> columns = new ArrayList<>(List.of(labels.roundColumn(), labels.company(),
                text.statementColumn, text.sectionColumn, text.lineColumn, text.levelColumn,
                text.amountColumn));
        writeHeader(sheet, head, columns.toArray(new String[0]));

        int index = 1;
        for (RoundReport round : report.rounds()) {
            for (Row row : round.rows()) {
                if (row.statements() == null) {
                    continue;
                }
                index = writeStatement(sheet, index, round.round(), row.company(), text.pnl,
                        row.statements().pnl(), text);
                index = writeStatement(sheet, index, round.round(), row.company(), text.cash,
                        row.statements().cashFlow(), text);
                index = writeStatement(sheet, index, round.round(), row.company(), text.balance,
                        row.statements().balance(), text);
            }
        }
        sizes(sheet, columns.size());
        sheet.setColumnWidth(1, 26 * 256);
        sheet.setColumnWidth(2, 24 * 256);
        sheet.setColumnWidth(4, 30 * 256);
    }

    private static int writeStatement(Sheet sheet, int index, int round, String company, String title,
                                      List<FinancialStatementsService.Row> rows, StatementText text) {
        for (FinancialStatementsService.Row line : rows) {
            org.apache.poi.ss.usermodel.Row sheetRow = sheet.createRow(index++);
            sheetRow.createCell(0).setCellValue(round);
            sheetRow.createCell(1).setCellValue(company);
            sheetRow.createCell(2).setCellValue(title);
            sheetRow.createCell(3).setCellValue(text.sections.getOrDefault(line.section(), ""));
            sheetRow.createCell(4).setCellValue(text.lines.getOrDefault(line.key(), line.key()));
            sheetRow.createCell(5).setCellValue(line.level());
            number(sheetRow, 6, line.amount());
        }
        return index;
    }

    private static void writeHeader(Sheet sheet, CellStyle style, String[] labels) {
        org.apache.poi.ss.usermodel.Row row = sheet.createRow(0);
        for (int i = 0; i < labels.length; i++) {
            org.apache.poi.ss.usermodel.Cell cell = row.createCell(i);
            cell.setCellValue(labels[i]);
            cell.setCellStyle(style);
        }
        sheet.createFreezePane(0, 1);
    }

    private static int number(org.apache.poi.ss.usermodel.Row row, int column, BigDecimal value) {
        row.createCell(column).setCellValue(value == null ? 0d : value.doubleValue());
        return column + 1;
    }

    private static void sizes(Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.setColumnWidth(i, 14 * 256);
        }
    }

    // ------------------------------------------------------------------ formatting

    private static String grouped(BigDecimal value) {
        return value == null ? "" : String.format("%,.0f", value.setScale(0, RoundingMode.HALF_UP));
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
