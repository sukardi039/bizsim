package com.bizsim.sim;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
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
 * The classroom debrief as a file (D13): six KPIs on the first page, the two "why" measures on the
 * second, plus the cost lines. It reads the same {@code round_result} and {@code round_statement}
 * rows the screen reads, so the export cannot disagree with the debrief.
 *
 * <p>The PDF embeds WenQuanYi Micro Hei: class content is authored (company and product names may be
 * Chinese), and a PDF that renders them as boxes is worse than no PDF.
 */
@Service
public class DebriefExportService {

    /** The named costs a statement carries; the profit line is revenue minus their sum. */
    private static final List<Map.Entry<String, String>> COST_LINES = List.of(
            Map.entry("cogs", "Cost of goods sold"),
            Map.entry("payroll", "Payroll"),
            Map.entry("marketing", "Marketing"),
            Map.entry("rnd_spend", "R&D"),
            Map.entry("quality_cost", "Quality upgrade"),
            Map.entry("fixed_cost", "Fixed cost"),
            Map.entry("holding_cost", "Stock holding"),
            Map.entry("hiring_cost", "Hiring and firing"),
            Map.entry("capacity_cost", "Capacity"),
            Map.entry("report_cost", "Market review"));

    private final JdbcTemplate jdbc;

    public DebriefExportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(long teamId, String company, String trainees, BigDecimal revenue, BigDecimal cost,
                      BigDecimal profit, BigDecimal cumulativeProfit, BigDecimal cash,
                      BigDecimal marketShare, BigDecimal brand, BigDecimal stock, BigDecimal lostSales,
                      BigDecimal capacityUtilisation, int rndCompleted, String status,
                      Map<String, BigDecimal> costLines) {
    }

    public record Report(long runId, String title, String scenarioKey, int round, Instant computedAt,
                         List<Row> rows) {
    }

    /** Assembles the report for one round of one run. Read-only. */
    public Report load(long tenantId, long runId, Integer requestedRound) {
        List<Map<String, Object>> meta = jdbc.query("""
                select s.name, r.scenario_key,
                       coalesce(?, (select max(number) from round
                                    where run_id = r.id and status = 'COMPUTED')) as round_number
                from run r join simulation s on s.id = r.simulation_id
                where r.id = ? and r.tenant_id = ?
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", rs.getString("name"));
            row.put("scenarioKey", rs.getString("scenario_key"));
            row.put("round", (Integer) rs.getObject("round_number"));
            return row;
        }, requestedRound, runId, tenantId);

        if (meta.isEmpty()) {
            throw ProblemException.notFound("run.not_found", "no such run");
        }
        Map<String, Object> head = meta.get(0);
        Integer round = (Integer) head.get("round");
        if (round == null) {
            throw ProblemException.conflict("round.none_computed", "no round has been computed yet");
        }

        Instant computedAt = jdbc.query("""
                select max(rr.computed_at) from round_result rr
                join round r on r.id = rr.round_id where r.run_id = ? and r.number = ?
                """, (rs, i) -> {
            Timestamp stamp = rs.getTimestamp(1);
            return stamp == null ? null : stamp.toInstant();
        }, runId, round).stream().findFirst().orElse(null);

        List<Row> rows = new ArrayList<>();
        jdbc.query("""
                select t.id as team_id, t.company_name,
                       coalesce((select string_agg(u.display_name, ', ' order by u.display_name)
                                 from player p join app_user u on u.id = p.user_id
                                 where p.team_id = t.id), '') as trainees,
                       (rr.score->>'revenue')::numeric            as revenue,
                       (rr.score->>'marketShare')::numeric        as market_share,
                       (rr.score->>'cumulativeProfit')::numeric   as cumulative_profit,
                       (rr.score->>'cash')::numeric               as cash,
                       (rr.score->>'brand')::numeric              as brand,
                       (rr.score->>'stock')::numeric              as stock,
                       (rr.score->>'lostSales')::numeric          as lost_sales,
                       (rr.score->>'capacityUtilisation')::numeric as capacity_utilisation,
                       (rr.score->>'rndCompleted')::int           as rnd_completed,
                       rr.score->>'status'                        as status,
                       s.cogs, s.payroll, s.marketing, s.rnd_spend, s.quality_cost, s.fixed_cost,
                       s.holding_cost, s.hiring_cost, s.capacity_cost, s.report_cost,
                       coalesce(s.profit, (rr.score->>'profit')::numeric) as profit
                from round r
                join round_result rr on rr.round_id = r.id
                join team t on t.id = rr.team_id
                left join round_statement s on s.round_id = r.id and s.team_id = t.id
                where r.run_id = ? and r.number = ?
                order by rr.total_score desc, t.id
                """, rs -> {
            Map<String, BigDecimal> costs = new LinkedHashMap<>();
            for (Map.Entry<String, String> line : COST_LINES) {
                costs.put(line.getKey(), nz(rs.getBigDecimal(line.getKey())));
            }
            BigDecimal cost = costs.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            rows.add(new Row(rs.getLong("team_id"), rs.getString("company_name"),
                    rs.getString("trainees"), nz(rs.getBigDecimal("revenue")), cost,
                    nz(rs.getBigDecimal("profit")), nz(rs.getBigDecimal("cumulative_profit")),
                    nz(rs.getBigDecimal("cash")), nz(rs.getBigDecimal("market_share")),
                    nz(rs.getBigDecimal("brand")), nz(rs.getBigDecimal("stock")),
                    nz(rs.getBigDecimal("lost_sales")), nz(rs.getBigDecimal("capacity_utilisation")),
                    rs.getInt("rnd_completed"), rs.getString("status"), costs));
        }, runId, round);

        return new Report(runId, (String) head.get("name"), (String) head.get("scenarioKey"), round,
                computedAt, rows);
    }

    // ------------------------------------------------------------------ PDF

    public byte[] pdf(Report report) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 32, 32, 30, 30);
            PdfWriter.getInstance(document, out);
            document.open();

            BaseFont regular = embedded();
            BaseFont bold = embedded();
            Font title = new Font(bold, 18);
            Font subtitle = new Font(regular, 10);
            Font head = new Font(bold, 9);
            Font body = new Font(regular, 9);

            document.add(new Paragraph(report.title(), title));
            document.add(new Paragraph(
                    "Run " + report.runId() + " · " + report.scenarioKey() + " · round "
                            + report.round() + (report.computedAt() == null ? ""
                            : " · computed " + report.computedAt()), subtitle));
            document.add(new Paragraph(" ", subtitle));

            document.add(new Paragraph("1. The class, this round", new Font(bold, 12)));
            PdfPTable kpi = table(9);
            header(kpi, head, "Company", "Trainee", "Revenue", "Cost", "Profit", "Cumulative",
                    "Cash", "Share %", "Brand");
            for (Row row : report.rows()) {
                cell(kpi, body, row.company());
                cell(kpi, body, row.trainees());
                money(kpi, body, row.revenue());
                money(kpi, body, row.cost());
                money(kpi, body, row.profit());
                money(kpi, body, row.cumulativeProfit());
                money(kpi, body, row.cash());
                cell(kpi, body, plain(row.marketShare()));
                cell(kpi, body, plain(row.brand()));
            }
            document.add(kpi);

            document.newPage();
            document.add(new Paragraph("2. Why the numbers moved", new Font(bold, 12)));
            PdfPTable why = table(6);
            header(why, head, "Company", "Stock on hand", "Lost sales", "Capacity used %",
                    "R&D completed", "Status");
            for (Row row : report.rows()) {
                cell(why, body, row.company());
                cell(why, body, plain(row.stock()));
                cell(why, body, plain(row.lostSales()));
                cell(why, body, plain(row.capacityUtilisation()));
                cell(why, body, String.valueOf(row.rndCompleted()));
                cell(why, body, row.status());
            }
            document.add(why);

            document.add(new Paragraph(" ", subtitle));
            document.add(new Paragraph("Cost lines", new Font(bold, 12)));
            PdfPTable costs = table(COST_LINES.size() + 1);
            List<String> names = new ArrayList<>();
            names.add("Company");
            for (Map.Entry<String, String> line : COST_LINES) {
                names.add(line.getValue());
            }
            header(costs, head, names.toArray(new String[0]));
            for (Row row : report.rows()) {
                cell(costs, body, row.company());
                for (Map.Entry<String, String> line : COST_LINES) {
                    money(costs, body, row.costLines().getOrDefault(line.getKey(), BigDecimal.ZERO));
                }
            }
            document.add(costs);

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the debrief PDF", e);
        }
    }

    /**
     * The bundled CJK face, embedded as a subset. The collection index (`,0`) belongs to the font
     * name passed to OpenPDF, not to the classpath resource.
     */
    private static BaseFont embedded() throws Exception {
        try (InputStream in = new ClassPathResource("fonts/wqy-microhei.ttc").getInputStream()) {
            return BaseFont.createFont("wqy-microhei.ttc,0", BaseFont.IDENTITY_H,
                    BaseFont.EMBEDDED, true, in.readAllBytes(), null);
        }
    }

    private static PdfPTable table(int columns) {
        PdfPTable table = new PdfPTable(columns);
        table.setWidthPercentage(100);
        table.setSpacingBefore(6);
        return table;
    }

    private static void header(PdfPTable table, Font font, String... labels) {
        for (String label : labels) {
            PdfPCell cell = new PdfPCell(new Phrase(label, font));
            cell.setBackgroundColor(new java.awt.Color(0xE8, 0xEE, 0xF6));
            cell.setPadding(4);
            table.addCell(cell);
        }
    }

    private static void cell(PdfPTable table, Font font, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
        cell.setPadding(4);
        table.addCell(cell);
    }

    private static void money(PdfPTable table, Font font, BigDecimal value) {
        PdfPCell cell = new PdfPCell(new Phrase(grouped(value), font));
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cell.setPadding(4);
        table.addCell(cell);
    }

    // ------------------------------------------------------------------ XLSX

    public byte[] xlsx(Report report) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle head = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            head.setFont(boldFont);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Sheet sheet = workbook.createSheet("Round " + report.round());
            String[] columns = {"Company", "Trainee", "Revenue", "Cost", "Profit", "Cumulative profit",
                    "Cash", "Market share %", "Brand", "Stock", "Lost sales", "Capacity used %",
                    "R&D completed", "Status"};
            writeHeader(sheet, head, columns);
            int r = 1;
            for (Row row : report.rows()) {
                org.apache.poi.ss.usermodel.Row line = sheet.createRow(r++);
                line.createCell(0).setCellValue(row.company());
                line.createCell(1).setCellValue(row.trainees());
                number(line, 2, row.revenue());
                number(line, 3, row.cost());
                number(line, 4, row.profit());
                number(line, 5, row.cumulativeProfit());
                number(line, 6, row.cash());
                number(line, 7, row.marketShare());
                number(line, 8, row.brand());
                number(line, 9, row.stock());
                number(line, 10, row.lostSales());
                number(line, 11, row.capacityUtilisation());
                line.createCell(12).setCellValue(row.rndCompleted());
                line.createCell(13).setCellValue(row.status());
            }
            sizes(sheet, columns.length);

            Sheet costs = workbook.createSheet("Cost lines");
            List<String> names = new ArrayList<>();
            names.add("Company");
            for (Map.Entry<String, String> line : COST_LINES) {
                names.add(line.getValue());
            }
            writeHeader(costs, head, names.toArray(new String[0]));
            r = 1;
            for (Row row : report.rows()) {
                org.apache.poi.ss.usermodel.Row line = costs.createRow(r++);
                line.createCell(0).setCellValue(row.company());
                int c = 1;
                for (Map.Entry<String, String> cost : COST_LINES) {
                    number(line, c++, row.costLines().getOrDefault(cost.getKey(), BigDecimal.ZERO));
                }
            }
            sizes(costs, names.size());

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the debrief workbook", e);
        }
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

    private static void number(org.apache.poi.ss.usermodel.Row row, int column, BigDecimal value) {
        row.createCell(column).setCellValue(value == null ? 0d : value.doubleValue());
    }

    private static void sizes(Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.setColumnWidth(i, 16 * 256);
        }
        sheet.setColumnWidth(0, 26 * 256);
        sheet.setColumnWidth(1, 26 * 256);
    }

    // ------------------------------------------------------------------ formatting

    private static String grouped(BigDecimal value) {
        return value == null ? "" : String.format("%,.2f", value.setScale(2, RoundingMode.HALF_UP));
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
