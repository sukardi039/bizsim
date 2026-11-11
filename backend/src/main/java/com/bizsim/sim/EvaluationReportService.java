package com.bizsim.sim;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.bizsim.web.ProblemException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A trainee's own development report: what their company did over the term, and what that says about
 * how they ran it — in the language the class is playing in.
 *
 * <p>Two things this service deliberately does not do. It never recomputes a figure: every number is
 * read from {@code round_statement} and {@code round_result}, the rows the screen and the debrief
 * read, so a report cannot disagree with the console. And it never compares the trainee with a named
 * competitor: market share is their own share and its movement, not anyone else's numbers (D21). The
 * class table (D51/D75) is the only place a trainee sees the others, and that is a rank.
 *
 * <p>The assessment is **rules over the data**, not generated prose: the thresholds are constants
 * below and are recorded in D76, so two identical terms always produce the same report and a trainer
 * can defend every sentence in front of the class.
 */
@Service
public class EvaluationReportService {

    /** One computed month of one company. */
    public record RoundFigures(int number, BigDecimal revenue, BigDecimal cogs, BigDecimal payroll,
                               BigDecimal marketing, BigDecimal rnd, BigDecimal fixed, BigDecimal holding,
                               BigDecimal hiring, BigDecimal capacity, BigDecimal quality,
                               BigDecimal report, BigDecimal profit, BigDecimal cashAfter,
                               BigDecimal openingCash, BigDecimal brandAfter, BigDecimal lostSales,
                               BigDecimal capacityUsed, BigDecimal capacityAvailable, BigDecimal stock,
                               BigDecimal marketShare, BigDecimal unitsSold, BigDecimal rndCompleted) {

        /** Everything the month paid out in cash: the cost of running the business. */
        BigDecimal cashCosts() {
            return payroll.add(marketing).add(rnd).add(fixed).add(holding).add(hiring).add(capacity)
                    .add(quality).add(report);
        }
    }

    /** The company, the reader and the term. */
    public record Term(long teamId, String company, String trainees, String session, String programme,
                       String lang, List<RoundFigures> rounds) {
    }

    public enum Band { STRONG, STEADY, WATCH }

    /** One dimension of the assessment: the band, and the numbers its sentence quotes. */
    public record Measure(String key, Band band, Map<String, String> values) {
    }

    /** Raw term totals, kept out of the prose so tests can assert them directly. */
    public record Totals(BigDecimal openingCash, BigDecimal finalCash, BigDecimal cashDelta,
                         BigDecimal minCash, BigDecimal avgCashCosts, BigDecimal bufferMonths,
                         BigDecimal revenueTotal, BigDecimal cogsTotal, BigDecimal profitTotal,
                         int profitableRounds, BigDecimal margin, BigDecimal marketingTotal,
                         BigDecimal rndTotal, BigDecimal qualityTotal, BigDecimal capacityTotal,
                         BigDecimal investmentTotal, BigDecimal revenuePerMarketing,
                         BigDecimal marketingShare, BigDecimal firstShare, BigDecimal lastShare,
                         BigDecimal shareDelta, BigDecimal utilisation, BigDecimal lostSalesTotal,
                         BigDecimal unitsSold, BigDecimal endStock, BigDecimal grossMargin,
                         BigDecimal rndCompleted) {
    }

    public record Evaluation(Term term, Totals totals, List<Measure> measures) {
    }

    // Thresholds (D76). Stated once, here, so the report and its documentation cannot drift.
    private static final BigDecimal FIVE_PERCENT = new BigDecimal("0.05");
    private static final BigDecimal TWO_PERCENT_LOSS = new BigDecimal("-0.02");
    private static final BigDecimal STRONG_BUFFER_MONTHS = new BigDecimal("3");
    private static final BigDecimal STEADY_BUFFER_MONTHS = new BigDecimal("2");
    private static final BigDecimal STRONG_UTILISATION = new BigDecimal("0.80");
    private static final BigDecimal STEADY_UTILISATION = new BigDecimal("0.60");
    private static final BigDecimal STRONG_GROSS_MARGIN = new BigDecimal("0.40");
    private static final BigDecimal STEADY_GROSS_MARGIN = new BigDecimal("0.20");
    /** Revenue per 1 of marketing: 5 means every unit spent on marketing came back fivefold. */
    private static final BigDecimal STRONG_RETURN_ON_MARKETING = new BigDecimal("5");
    /** A share move smaller than one point either way counts as holding position. */
    private static final BigDecimal ONE_POINT = BigDecimal.ONE;

    private final JdbcTemplate jdbc;

    public EvaluationReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ analysis

    /**
     * Reads one company's computed rounds and assesses them.
     *
     * @throws ProblemException 404 when the team is not in this run of this tenant, 409 when the
     *                          session has no computed round yet
     */
    public Evaluation evaluate(long tenantId, long runId, long teamId) {
        Term term = loadTerm(tenantId, runId, teamId);
        if (term == null) {
            throw ProblemException.notFound("team.not_found", "no such company in this session");
        }
        if (term.rounds().isEmpty()) {
            throw ProblemException.conflict("report.no_rounds",
                    "this session has no computed round yet, so there is nothing to report on");
        }
        Totals totals = totals(term.rounds());
        return new Evaluation(term, totals, measures(term.rounds(), totals));
    }

    private Term loadTerm(long tenantId, long runId, long teamId) {
        List<Map<String, Object>> head = jdbc.queryForList("""
                select t.company_name,
                       coalesce((select string_agg(u.display_name, ' · ' order by u.display_name)
                                 from player p join app_user u on u.id = p.user_id
                                 where p.team_id = t.id), '') as trainees,
                       sim.name as session,
                       coalesce(pkg.name, '') as programme,
                       r.lang
                from team t
                join run r on r.id = t.run_id
                join simulation sim on sim.id = r.simulation_id
                left join simulation_package pkg on pkg.id = r.package_id
                where t.id = ? and t.run_id = ? and r.tenant_id = ?
                """, teamId, runId, tenantId);
        if (head.isEmpty()) {
            return null;
        }
        Map<String, Object> row = head.get(0);
        List<RoundFigures> rounds = jdbc.query("""
                select r.number, s.revenue, s.cogs, s.payroll, s.marketing, s.rnd_spend, s.fixed_cost,
                       s.holding_cost, s.hiring_cost, s.capacity_cost, s.quality_cost, s.report_cost,
                       s.profit, s.cash_after, s.opening_cash, s.brand_after, s.lost_sales,
                       s.capacity_used, s.capacity_available,
                       coalesce((rr.score->>'stock')::numeric, 0)        as stock,
                       coalesce((rr.score->>'marketShare')::numeric, 0)  as market_share,
                       coalesce((rr.score->>'unitsSold')::numeric, 0)    as units_sold,
                       coalesce((rr.score->>'rndCompleted')::numeric, 0) as rnd_completed
                from round r
                join round_statement s on s.round_id = r.id and s.team_id = ?
                join round_result rr on rr.round_id = r.id and rr.team_id = s.team_id
                where r.run_id = ? and r.status = 'COMPUTED'
                order by r.number
                """, (rs, i) -> new RoundFigures(
                rs.getInt("number"), rs.getBigDecimal("revenue"), rs.getBigDecimal("cogs"),
                rs.getBigDecimal("payroll"), rs.getBigDecimal("marketing"), rs.getBigDecimal("rnd_spend"),
                rs.getBigDecimal("fixed_cost"), rs.getBigDecimal("holding_cost"),
                rs.getBigDecimal("hiring_cost"), rs.getBigDecimal("capacity_cost"),
                rs.getBigDecimal("quality_cost"), rs.getBigDecimal("report_cost"),
                rs.getBigDecimal("profit"), rs.getBigDecimal("cash_after"),
                rs.getBigDecimal("opening_cash"), rs.getBigDecimal("brand_after"),
                rs.getBigDecimal("lost_sales"), rs.getBigDecimal("capacity_used"),
                rs.getBigDecimal("capacity_available"), rs.getBigDecimal("stock"),
                rs.getBigDecimal("market_share"), rs.getBigDecimal("units_sold"),
                rs.getBigDecimal("rnd_completed")), teamId, runId);
        return new Term(teamId, String.valueOf(row.get("company_name")),
                String.valueOf(row.get("trainees")), String.valueOf(row.get("session")),
                String.valueOf(row.get("programme")), String.valueOf(row.get("lang")), rounds);
    }

    Totals totals(List<RoundFigures> rounds) {
        RoundFigures first = rounds.get(0);
        RoundFigures last = rounds.get(rounds.size() - 1);
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cogs = BigDecimal.ZERO;
        BigDecimal profit = BigDecimal.ZERO;
        BigDecimal marketing = BigDecimal.ZERO;
        BigDecimal rnd = BigDecimal.ZERO;
        BigDecimal quality = BigDecimal.ZERO;
        BigDecimal capacity = BigDecimal.ZERO;
        BigDecimal lost = BigDecimal.ZERO;
        BigDecimal sold = BigDecimal.ZERO;
        BigDecimal rndDone = BigDecimal.ZERO;
        BigDecimal cashCosts = BigDecimal.ZERO;
        BigDecimal used = BigDecimal.ZERO;
        BigDecimal available = BigDecimal.ZERO;
        BigDecimal minCash = null;
        int profitable = 0;
        for (RoundFigures r : rounds) {
            revenue = revenue.add(r.revenue());
            cogs = cogs.add(r.cogs());
            profit = profit.add(r.profit());
            marketing = marketing.add(r.marketing());
            rnd = rnd.add(r.rnd());
            quality = quality.add(r.quality());
            capacity = capacity.add(r.capacity());
            lost = lost.add(r.lostSales());
            sold = sold.add(r.unitsSold());
            rndDone = rndDone.add(r.rndCompleted());
            cashCosts = cashCosts.add(r.cashCosts());
            used = used.add(r.capacityUsed());
            available = available.add(r.capacityAvailable());
            if (r.profit().signum() > 0) {
                profitable++;
            }
            minCash = minCash == null || r.cashAfter().compareTo(minCash) < 0 ? r.cashAfter() : minCash;
        }
        BigDecimal avgCashCosts = div(cashCosts, BigDecimal.valueOf(rounds.size()), 2);
        BigDecimal cashDelta = last.cashAfter().subtract(first.openingCash());
        BigDecimal investment = rnd.add(quality).add(capacity);
        return new Totals(
                first.openingCash(), last.cashAfter(), cashDelta,
                minCash == null ? BigDecimal.ZERO : minCash, avgCashCosts,
                avgCashCosts.signum() > 0 ? div(last.cashAfter(), avgCashCosts, 2) : BigDecimal.ZERO,
                revenue, cogs, profit, profitable,
                revenue.signum() > 0 ? div(profit, revenue, 4) : BigDecimal.ZERO,
                marketing, rnd, quality, capacity, investment,
                marketing.signum() > 0 ? div(revenue, marketing, 2) : null,
                revenue.signum() > 0 ? div(marketing, revenue, 4) : BigDecimal.ZERO,
                first.marketShare(), last.marketShare(), last.marketShare().subtract(first.marketShare()),
                div(used, available, 4),
                lost, sold, last.stock(),
                revenue.signum() > 0 ? div(revenue.subtract(cogs), revenue, 4) : BigDecimal.ZERO,
                rndDone);
    }

    List<Measure> measures(List<RoundFigures> rounds, Totals t) {
        List<Measure> out = new ArrayList<>();

        // 1. Liquidity — the ranking measure of the class table, so it leads here too.
        Band cashBand = t.cashDelta().signum() > 0 && t.bufferMonths().compareTo(STRONG_BUFFER_MONTHS) >= 0
                ? Band.STRONG
                : t.cashDelta().signum() >= 0 || t.bufferMonths().compareTo(STEADY_BUFFER_MONTHS) >= 0
                        ? Band.STEADY : Band.WATCH;
        out.add(new Measure("liquidity", cashBand, values(
                "cash", money(t.finalCash()), "delta", signedMoney(t.cashDelta()),
                "opening", money(t.openingCash()), "months", number(t.bufferMonths(), 1),
                "low", money(t.minCash()))));

        // 2. Profitability — accrual, so it is the second story, not the first.
        Band profitBand = t.profitTotal().signum() > 0 && t.margin().compareTo(FIVE_PERCENT) >= 0
                ? Band.STRONG
                : t.profitTotal().signum() > 0 || t.margin().compareTo(TWO_PERCENT_LOSS) >= 0
                        ? Band.STEADY : Band.WATCH;
        out.add(new Measure("profit", profitBand, values(
                "profit", signedMoney(t.profitTotal()), "margin", percent(t.margin()),
                "rounds", String.valueOf(t.profitableRounds()), "of", String.valueOf(rounds.size()),
                "revenue", money(t.revenueTotal()))));

        // 3. Market management — share won or lost, and what the marketing money bought.
        Band marketBand = t.shareDelta().signum() > 0
                && (t.revenuePerMarketing() == null
                || t.revenuePerMarketing().compareTo(STRONG_RETURN_ON_MARKETING) >= 0)
                ? Band.STRONG
                : t.shareDelta().compareTo(ONE_POINT.negate()) >= 0 ? Band.STEADY : Band.WATCH;
        out.add(new Measure("market", marketBand, values(
                "share", sharePercent(t.lastShare()), "delta", points(t.shareDelta()),
                "marketing", money(t.marketingTotal()),
                "return", t.revenuePerMarketing() == null ? "—" : number(t.revenuePerMarketing(), 1),
                "shareOfRevenue", percent(t.marketingShare()),
                "lost", number(t.lostSalesTotal(), 0))));

        // 4. Operations — capacity pushed to demand, not into the warehouse.
        Band opsBand = t.utilisation().compareTo(STRONG_UTILISATION) >= 0
                && t.lostSalesTotal().compareTo(BigDecimal.ZERO) == 0
                ? Band.STRONG
                : t.utilisation().compareTo(STEADY_UTILISATION) >= 0 ? Band.STEADY : Band.WATCH;
        out.add(new Measure("operations", opsBand, values(
                "utilisation", percent(t.utilisation()), "lost", number(t.lostSalesTotal(), 0),
                "sold", number(t.unitsSold(), 0), "stock", number(t.endStock(), 0))));

        // 5. Pricing — what a unit earned over what it cost to make.
        Band priceBand = t.grossMargin().compareTo(STRONG_GROSS_MARGIN) >= 0
                ? Band.STRONG
                : t.grossMargin().compareTo(STEADY_GROSS_MARGIN) >= 0 ? Band.STEADY : Band.WATCH;
        out.add(new Measure("pricing", priceBand, values(
                "margin", percent(t.grossMargin()), "revenue", money(t.revenueTotal()),
                "cogs", money(t.cogsTotal()))));

        // 6. Investment — money put into the company rather than taken out of it.
        Band investBand = t.investmentTotal().signum() > 0 && t.profitTotal().signum() > 0
                ? Band.STRONG
                : t.investmentTotal().signum() > 0 ? Band.STEADY : Band.WATCH;
        out.add(new Measure("investment", investBand, values(
                "invested", money(t.investmentTotal()), "rnd", money(t.rndTotal()),
                "quality", money(t.qualityTotal()), "capacity", money(t.capacityTotal()),
                "completed", number(t.rndCompleted(), 0))));
        return out;
    }

    // ------------------------------------------------------------------ the document

    /** The report as a PDF, in the language of the class the trainee is playing in. */
    public byte[] pdf(Evaluation evaluation, Labels labels) {
        Term term = evaluation.term();
        Totals totals = evaluation.totals();
        Document document = new Document(PageSize.A4, 42, 42, 48, 42);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            BaseFont face = embedded();
            Font title = new Font(face, 18);
            Font sub = new Font(face, 9);
            Font head = new Font(face, 11);
            Font body = new Font(face, 9);
            Font small = new Font(face, 8);

            Paragraph heading = new Paragraph(labels.t("title"), title);
            heading.setSpacingAfter(6);
            document.add(heading);
            Paragraph forLine = new Paragraph(labels.t("for", term.company()), head);
            forLine.setSpacingAfter(2);
            document.add(forLine);
            Paragraph meta = new Paragraph(labels.t("meta",
                    term.trainees().isBlank() ? "—" : term.trainees(),
                    term.programme().isBlank() ? term.session() : term.programme(),
                    String.valueOf(term.rounds().size())), sub);
            meta.setSpacingAfter(14);
            document.add(meta);

            // The verdict: six dimensions, each one sentence of evidence.
            for (Measure measure : evaluation.measures()) {
                document.add(measureParagraph(measure, labels, head, body));
            }

            document.add(new Paragraph(labels.t("adviceTitle"), head));
            int shown = 0;
            for (Measure measure : byNeed(evaluation.measures())) {
                if (shown == 3) {
                    break;
                }
                Paragraph advice = new Paragraph(labels.t("advice", labels.t("dim." + measure.key()),
                        labels.t("adviceText." + measure.key())), body);
                advice.setIndentationLeft(10);
                advice.setSpacingAfter(4);
                document.add(advice);
                shown++;
            }

            document.newPage();
            document.add(new Paragraph(labels.t("roundsTitle"), head));
            document.add(new Paragraph(" ", sub));
            document.add(roundTable(term.rounds(), labels, body, small));
            Paragraph closing = new Paragraph(labels.t("closing", money(totals.finalCash()),
                    signedMoney(totals.profitTotal())), body);
            closing.setSpacingBefore(12);
            document.add(closing);
            Paragraph note = new Paragraph(labels.t("note"), small);
            note.setSpacingBefore(14);
            document.add(note);
            document.add(new Paragraph(labels.t("generated", Instant.now().toString().substring(0, 10)), small));
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the development report", e);
        } finally {
            document.close();
        }
        return out.toByteArray();
    }

    /** WATCH first: the advice section should lead with what to change. */
    private static List<Measure> byNeed(List<Measure> measures) {
        List<Measure> sorted = new ArrayList<>(measures);
        sorted.sort(Comparator.comparingInt(m -> switch (m.band()) {
            case WATCH -> 0;
            case STEADY -> 1;
            case STRONG -> 2;
        }));
        return sorted;
    }

    private Paragraph measureParagraph(Measure measure, Labels labels, Font head, Font body) {
        Paragraph p = new Paragraph();
        p.add(new Phrase(labels.t("dim." + measure.key()) + " · "
                + labels.t("band." + measure.band().name()) + "\n", head));
        p.add(new Phrase(labels.t(measure.key(), measure.values()), body));
        p.setSpacingAfter(8);
        return p;
    }

    private PdfPTable roundTable(List<RoundFigures> rounds, Labels labels, Font body, Font small) {
        PdfPTable table = new PdfPTable(6);
        table.setWidthPercentage(100);
        setWidths(table, List.of(0.7f, 1.5f, 1.5f, 1.5f, 1.5f, 1.1f));
        for (String column : List.of("col.round", "col.revenue", "col.cost", "col.profit", "col.cash",
                "col.share")) {
            PdfPCell cell = new PdfPCell(new Phrase(labels.t(column), small));
            cell.setBackgroundColor(new java.awt.Color(0xE8, 0xEE, 0xF6));
            cell.setPadding(3);
            table.addCell(cell);
        }
        for (RoundFigures r : rounds) {
            table.addCell(cell(String.valueOf(r.number()), body));
            table.addCell(cell(money(r.revenue()), body));
            table.addCell(cell(money(r.revenue().subtract(r.profit())), body));
            table.addCell(cell(signedMoney(r.profit()), body));
            table.addCell(cell(money(r.cashAfter()), body));
            table.addCell(cell(sharePercent(r.marketShare()), body));
        }
        return table;
    }

    private static PdfPCell cell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(3);
        return cell;
    }

    private static void setWidths(PdfPTable table, List<Float> weights) {
        float[] values = new float[weights.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = weights.get(i);
        }
        table.setWidths(values);
    }

    private static BaseFont embedded() throws Exception {
        try (InputStream in = new ClassPathResource("fonts/wqy-microhei.ttc").getInputStream()) {
            return BaseFont.createFont("wqy-microhei.ttc,0", BaseFont.IDENTITY_H,
                    BaseFont.EMBEDDED, true, in.readAllBytes(), null);
        }
    }

    // ------------------------------------------------------------------ formatting

    static BigDecimal div(BigDecimal a, BigDecimal b, int scale) {
        if (b == null || b.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return a.divide(b, scale, RoundingMode.HALF_UP);
    }

    static String money(BigDecimal value) {
        return String.format("%,.0f", value == null ? BigDecimal.ZERO : value);
    }

    static String signedMoney(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        return (v.signum() > 0 ? "+" : "") + money(v);
    }

    static String number(BigDecimal value, int decimals) {
        return String.format("%,." + decimals + "f", value == null ? BigDecimal.ZERO : value);
    }

    static String percent(BigDecimal fraction) {
        return number(fraction.multiply(new BigDecimal("100")), 1) + "%";
    }

    /** Market share arrives as a percentage already (score.marketShare = sold × 100 / pool). */
    static String sharePercent(BigDecimal share) {
        return number(share, 1) + "%";
    }

    static String points(BigDecimal share) {
        String sign = share.signum() > 0 ? "+" : "";
        return sign + number(share, 1) + " pt";
    }

    private static Map<String, String> values(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    // ------------------------------------------------------------------ wording

    /** The document's text, in the class's language. Placeholders are {@code {name}} etc. */
    public record Labels(String code, Map<String, String> text) {

        public String t(String key, Object... args) {
            String out = text.getOrDefault(key, key);
            for (int i = 0; i < args.length; i++) {
                out = out.replace("{" + i + "}", String.valueOf(args[i]));
            }
            return out;
        }

        /** For the dimension sentences, which are filled from a named map rather than by position. */
        public String t(String key, Map<String, String> named) {
            String out = text.getOrDefault(key, key);
            for (Map.Entry<String, String> entry : named.entrySet()) {
                out = out.replace("{" + entry.getKey() + "}", entry.getValue());
            }
            return out;
        }

        public static Labels of(String code) {
            return "zh".equalsIgnoreCase(code) ? ZH : EN;
        }

        private static final Labels EN = new Labels("en", Map.ofEntries(
                Map.entry("title", "Development report"),
                Map.entry("for", "How {0} ran its company this term"),
                Map.entry("meta", "Trainee: {0} · Programme: {1} · {2} computed rounds"),
                Map.entry("band.STRONG", "strong"),
                Map.entry("band.STEADY", "steady"),
                Map.entry("band.WATCH", "needs attention"),
                Map.entry("dim.liquidity", "Liquidity"),
                Map.entry("dim.profit", "Profitability"),
                Map.entry("dim.market", "Market management"),
                Map.entry("dim.operations", "Operations"),
                Map.entry("dim.pricing", "Pricing"),
                Map.entry("dim.investment", "Investment"),
                Map.entry("adviceTitle", "What to try next term"),
                Map.entry("advice", "— {0}: {1}"),
                Map.entry("adviceText.liquidity", "commit only cash you actually hold — fixed costs are "
                        + "charged every round whether you produce or not, and the class table ranks cash."),
                Map.entry("adviceText.profit", "aim for a margin above 5% before chasing volume; growth at a "
                        + "thin margin is what empties the bank."),
                Map.entry("adviceText.market", "measure marketing by the share it moves — if share does not "
                        + "follow the spend, the money went to demand you cannot serve."),
                Map.entry("adviceText.operations", "match capacity to the demand you expect: idle capacity "
                        + "costs money, unmet demand hands the sale to a competitor."),
                Map.entry("adviceText.pricing", "price from unit cost plus a target margin, not from last "
                        + "month's price."),
                Map.entry("adviceText.investment", "spread investment across the term — a project that "
                        + "completes after the final round never pays back."),
                Map.entry("roundsTitle", "Round by round"),
                Map.entry("col.round", "Round"),
                Map.entry("col.revenue", "Revenue"),
                Map.entry("col.cost", "Cost"),
                Map.entry("col.profit", "Profit"),
                Map.entry("col.cash", "Cash after"),
                Map.entry("col.share", "Share"),
                Map.entry("closing", "You finished with {0} in the bank and {1} of cumulative profit."),
                Map.entry("note", "Every figure is taken from the round statements this session computed; "
                        + "nothing here is re-estimated. Your position in the class is the separate class "
                        + "table your facilitator shows."),
                Map.entry("generated", "Generated {0}"),
                Map.entry("liquidity", "Cash finished at {cash}, {delta} against the opening {opening} — "
                        + "about {months} months of running costs, dipping to {low} at its lowest."),
                Map.entry("profit", "Cumulative profit {profit} on {revenue} of revenue, a margin of "
                        + "{margin}; {rounds} of {of} rounds were profitable."),
                Map.entry("market", "Share ended at {share} ({delta}). {marketing} of marketing returned "
                        + "{return} of revenue per unit spent, {shareOfRevenue} of revenue, and {lost} units "
                        + "of demand went unserved."),
                Map.entry("operations", "Capacity was used at {utilisation}, {sold} units were sold, {lost} "
                        + "units of demand were lost, and {stock} units were left in stock at the end."),
                Map.entry("pricing", "Revenue {revenue} against {cogs} of goods sold — a gross margin of "
                        + "{margin} before running costs."),
                Map.entry("investment", "{invested} went into the business: {rnd} research, {quality} "
                        + "quality, {capacity} capacity; {completed} research projects completed.")));

        private static final Labels ZH = new Labels("zh", Map.ofEntries(
                Map.entry("title", "经营发展报告"),
                Map.entry("for", "本学期 {0} 的经营情况"),
                Map.entry("meta", "学员：{0} · 课程：{1} · 已结算 {2} 轮"),
                Map.entry("band.STRONG", "优秀"),
                Map.entry("band.STEADY", "稳健"),
                Map.entry("band.WATCH", "需要关注"),
                Map.entry("dim.liquidity", "资金流动性"),
                Map.entry("dim.profit", "盈利能力"),
                Map.entry("dim.market", "市场管理"),
                Map.entry("dim.operations", "运营效率"),
                Map.entry("dim.pricing", "定价水平"),
                Map.entry("dim.investment", "投资情况"),
                Map.entry("adviceTitle", "下一阶段可以尝试"),
                Map.entry("advice", "— {0}：{1}"),
                Map.entry("adviceText.liquidity", "只承诺手上真正持有的现金——无论是否生产，固定成本每轮都会发生，"
                        + "而班级排名看的正是现金。"),
                Map.entry("adviceText.profit", "在追求销量之前先把利润率做到 5% 以上；薄利增长会掏空现金。"),
                Map.entry("adviceText.market", "用市场份额的变化衡量营销投入；投入增加而份额不动，说明钱花在了"
                        + "你服务不了的需求上。"),
                Map.entry("adviceText.operations", "让产能与预期的需求匹配：闲置产能是成本，未满足的需求会把生意"
                        + "送给竞争对手。"),
                Map.entry("adviceText.pricing", "以单位成本加目标利润来定价，而不是沿用上个月的价格。"),
                Map.entry("adviceText.investment", "把投资分散在整个学期——在最后一轮之后才完成的研发永远收不回来。"),
                Map.entry("roundsTitle", "逐轮数据"),
                Map.entry("col.round", "轮次"),
                Map.entry("col.revenue", "收入"),
                Map.entry("col.cost", "成本"),
                Map.entry("col.profit", "利润"),
                Map.entry("col.cash", "期末现金"),
                Map.entry("col.share", "市场份额"),
                Map.entry("closing", "期末现金为 {0}，累计利润为 {1}。"),
                Map.entry("note", "所有数字均取自本场模拟各轮已结算的报表，未做任何重新估算。在班级中的名次见"
                        + "教师展示的班级排名表。"),
                Map.entry("generated", "生成日期 {0}"),
                Map.entry("liquidity", "期末现金为 {cash}，相对期初 {opening} 变化 {delta}；约合 {months} 个月的"
                        + "运营支出，期间最低降至 {low}。"),
                Map.entry("profit", "累计利润 {profit}，收入 {revenue}，利润率 {margin}；{of} 轮中有 {rounds} 轮盈利。"),
                Map.entry("market", "期末市场份额 {share}（{delta}）。营销投入 {marketing}，每单位投入带来 {return} "
                        + "的收入，占收入 {shareOfRevenue}，另有 {lost} 单位需求未能满足。"),
                Map.entry("operations", "产能利用率 {utilisation}，售出 {sold} 单位，未满足需求 {lost} 单位，"
                        + "期末库存 {stock} 单位。"),
                Map.entry("pricing", "收入 {revenue}，销货成本 {cogs}，在运营费用之前的毛利率为 {margin}。"),
                Map.entry("investment", "投入 {invested}：研发 {rnd}、质量 {quality}、产能 {capacity}；"
                        + "完成研发项目 {completed} 个。")));
    }
}
