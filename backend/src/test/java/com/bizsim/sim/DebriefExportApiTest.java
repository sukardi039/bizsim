package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D13/D43: the debrief leaves the screen — every computed round with its submission, its results and
 * its cost lines, rendering authored Chinese content, and scoped correctly for a trainee (D21).
 */
@SpringBootTest
@AutoConfigureMockMvc
class DebriefExportApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private long runId;
    private long teamOne;
    private long teamTwo;
    private String facilitatorToken;
    private String playerToken;

    @BeforeEach
    void seed() throws Exception {
        for (String table : List.of("round_snapshot", "facilitator_action", "round_statement",
                "round_result", "decision", "round", "player", "company_product", "company_staff",
                "company_rnd", "team", "run", "simulation", "app_user", "tenant")) {
            jdbc.update("delete from " + table);
        }
        long tenant = jdbc.queryForObject(
                "insert into tenant (code, name) values ('acme', 'Acme') returning id", Long.class);
        user(tenant, "trainer@acme.test", "FACILITATOR");
        user(tenant, "one@acme.test", "PLAYER");
        user(tenant, "two@acme.test", "PLAYER");
        user(tenant, "admin@acme.test", "ADMIN");
        facilitatorToken = login("trainer@acme.test");
        playerToken = login("one@acme.test");
        String adminToken = login("admin@acme.test");

        // A Chinese company name on purpose: the PDF must render authored content, not boxes.
        MvcResult run = mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(idOf("trainer@acme.test")))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "十月班",
                                "scenarioKey", "standard-v1",
                                "teams", List.of(
                                        Map.of("name", "Alpha", "companyName", "阿尔法制造"),
                                        Map.of("name", "Beta", "companyName", "Beta Corp"))))))
                .andExpect(status().isCreated())
                .andReturn();
        runId = json.readTree(run.getResponse().getContentAsString()).get("id").asLong();
        teamOne = jdbc.queryForObject(
                "select id from team where run_id = ? order by id limit 1", Long.class, runId);
        teamTwo = jdbc.queryForObject(
                "select id from team where run_id = ? order by id desc limit 1", Long.class, runId);

        addPlayer(teamOne, "one@acme.test");

        openRound();
        submit(teamOne, payload(600, 150, 30, 20_000_000L, 30)).andExpect(status().isOk());
        compute();
    }

    // ------------------------------------------------------------------ the class report

    @Test
    void the_pdf_renders_the_class_including_its_chinese_names() throws Exception {
        MvcResult result = mvc.perform(get("/api/runs/" + runId + "/debrief/export")
                        .param("format", "pdf")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(new String(body, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains("bizsim-debrief-run" + runId + ".pdf");

        String text = allText(body);
        assertThat(text).contains("阿尔法制造");
        assertThat(text).contains("Beta Corp");
        // The facilitator's two summaries (D46): by trainee, and by month.
        assertThat(text).contains("Summary by trainee");
        assertThat(text).contains("Comparison by month");
        assertThat(text).contains("Total");
        // Each round carries three blocks (D43): the plan, the money, the cost lines.
        assertThat(text).contains("Round 1");
        assertThat(text).contains("What each company sent");
        assertThat(text).contains("Comparison by month");
        assertThat(text).contains("Cost lines");
        assertThat(text).contains("Revenue").contains("Profit").contains("Cash");
        // D88 scoping: the class debrief is a projection document — its statements are on the screen,
        // and only the company report carries them in print.
        assertThat(text).doesNotContain("The three statements");
    }

    @Test
    void the_workbook_opens_with_the_same_numbers() throws Exception {
        MvcResult result = mvc.perform(get("/api/runs/" + runId + "/debrief/export")
                        .param("format", "xlsx")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(result.getResponse().getContentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(body))) {
            Sheet results = workbook.getSheet("Results");
            assertThat(results.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Round");
            assertThat(results.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Company");
            assertThat(results.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(1);
            assertThat(results.getRow(1).getCell(1).getStringCellValue()).isIn("阿尔法制造", "Beta Corp");
            // Two companies, one computed round.
            assertThat(results.getLastRowNum()).isEqualTo(2);

            assertThat(workbook.getSheet("By trainee")).isNotNull();
            Sheet submissions = workbook.getSheet("Submissions");
            assertThat(submissions.getRow(0).getCell(2).getStringCellValue()).isEqualTo("Trainee");
            assertThat(workbook.getSheet("Cost lines")).isNotNull();
        }
    }

    @Test
    void every_computed_round_is_in_the_export() throws Exception {
        // Round 2: open, decide, compute.
        openRound();
        submit(teamOne, payload(700, 120, 25, 15_000_000L, 30)).andExpect(status().isOk());
        compute();

        MvcResult result = mvc.perform(get("/api/runs/" + runId + "/debrief/export")
                        .param("format", "xlsx")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andReturn();

        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            Sheet results = workbook.getSheet("Results");
            // 2 rounds × 2 companies.
            assertThat(results.getLastRowNum()).isEqualTo(4);
            assertThat(results.getRow(3).getCell(0).getNumericCellValue()).isEqualTo(2);
            assertThat(workbook.getSheet("Submissions").getLastRowNum()).isEqualTo(4);
        }

        String text = allText(resultPdf(facilitatorToken));
        assertThat(text).contains("Round 2");
    }

    // ------------------------------------------------------------------ the trainee's own report

    @Test
    void a_trainee_gets_their_own_company_and_nothing_else() throws Exception {
        openRound();
        submit(teamOne, payload(700, 120, 25, 15_000_000L, 30)).andExpect(status().isOk());
        compute();

        MvcResult result = mvc.perform(get("/api/runs/" + runId + "/debrief/export")
                        .param("format", "pdf")
                        .header("Authorization", "Bearer " + playerToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains("bizsim-results-run" + runId + ".pdf");

        byte[] body = result.getResponse().getContentAsByteArray();
        String text = allText(body);
        assertThat(text).contains("阿尔法制造");
        // D21: no competitor, and no market share, reaches a trainee.
        assertThat(text).doesNotContain("Beta Corp");
        assertThat(text).doesNotContain("Share");
        // D88: the three statements travel with the company report, in print.
        assertThat(text).contains("The three statements");
        // D92: once for the whole term, a column per round — not repeated on every round page.
        assertThat(text).contains("The three statements, every round");
        assertThat(occurrences(text, "Operating profit")).isEqualTo(1);
        assertThat(occurrences(text, "Gross profit")).isEqualTo(1);
        assertThat(text).contains("Profit and loss").contains("Cash flow statement")
                .contains("Assets and liabilities");
        assertThat(text).contains("Gross profit").contains("Operating profit");
        assertThat(text).contains("Net cash from operations").contains("Total assets")
                .contains("Total liabilities and equity");
        // One page per round, each opening with that month's summary (D46); the statements flow on.
        PdfReader reader = new PdfReader(body);
        assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(2);
        String firstPage = new PdfTextExtractor(reader).getTextFromPage(1);
        assertThat(firstPage).contains("Round 1").contains("The month's summary")
                .contains("What you sent").contains("Cost lines");
        assertThat(text).contains("Round 2").contains("The month's summary");

        MvcResult workbookResult = mvc.perform(get("/api/runs/" + runId + "/debrief/export")
                        .param("format", "xlsx")
                        .header("Authorization", "Bearer " + playerToken))
                .andExpect(status().isOk())
                .andReturn();
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(workbookResult.getResponse().getContentAsByteArray()))) {
            Sheet results = workbook.getSheet("Results");
            // Two computed rounds, one company: two rows, and no market-share column.
            assertThat(results.getLastRowNum()).isEqualTo(2);
            assertThat(results.getRow(1).getCell(1).getStringCellValue()).isEqualTo("阿尔法制造");
            List<String> headers = new java.util.ArrayList<>();
            results.getRow(0).forEach(cell -> headers.add(cell.getStringCellValue()));
            assertThat(headers).doesNotContain("Market share %");

            // D88: every line of the three statements, ready to filter or pivot.
            Sheet statements = workbook.getSheet("Statements");
            assertThat(statements).isNotNull();
            List<String> statementHeaders = new java.util.ArrayList<>();
            statements.getRow(0).forEach(cell -> statementHeaders.add(cell.getStringCellValue()));
            assertThat(statementHeaders).containsExactly("Round", "Company", "Statement", "Section",
                    "Line", "Level", "Amount");
            boolean operatingProfit = false;
            boolean balanceTotal = false;
            for (int row = 1; row <= statements.getLastRowNum(); row++) {
                String line = statements.getRow(row).getCell(4).getStringCellValue();
                String statement = statements.getRow(row).getCell(2).getStringCellValue();
                operatingProfit |= "Operating profit".equals(line) && "Profit and loss".equals(statement);
                balanceTotal |= "Total liabilities and equity".equals(line)
                        && "Assets and liabilities".equals(statement);
            }
            assertThat(operatingProfit).as("the profit and loss travels").isTrue();
            assertThat(balanceTotal).as("the balance sheet travels").isTrue();
        }
    }

    @Test
    void the_document_follows_the_readers_language() throws Exception {
        MvcResult zh = mvc.perform(get("/api/runs/" + runId + "/debrief/export")
                        .param("format", "pdf")
                        .param("locale", "zh")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andReturn();
        String chinese = allText(zh.getResponse().getContentAsByteArray());
        assertThat(chinese).contains("全班复盘");
        assertThat(chinese).contains("第 1 轮");
        assertThat(chinese).contains("各家提交");
        assertThat(chinese).contains("按月度对比");
        assertThat(chinese).contains("成本明细");
        assertThat(chinese).contains("收入");
        // Authored content is untouched: the company name is the same in either language.
        assertThat(chinese).contains("阿尔法制造");

        MvcResult workbook = mvc.perform(get("/api/runs/" + runId + "/debrief/export")
                        .param("format", "xlsx")
                        .param("locale", "zh")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andReturn();
        try (XSSFWorkbook opened = new XSSFWorkbook(
                new ByteArrayInputStream(workbook.getResponse().getContentAsByteArray()))) {
            assertThat(opened.getSheet("结果")).isNotNull();
            assertThat(opened.getSheet("提交")).isNotNull();
            assertThat(opened.getSheet("成本明细")).isNotNull();
            assertThat(opened.getSheet("结果").getRow(0).getCell(1).getStringCellValue()).isEqualTo("公司");
        }

        // No locale asked for: English, as before.
        String english = allText(resultPdf(facilitatorToken));
        assertThat(english).contains("What each company sent");

        // D88 in Chinese: the statements are a full set of words, not a translation of the screen.
        MvcResult zhCompany = mvc.perform(get("/api/runs/" + runId + "/debrief/export")
                        .param("format", "pdf")
                        .param("locale", "zh")
                        .header("Authorization", "Bearer " + playerToken))
                .andExpect(status().isOk())
                .andReturn();
        String zhText = allText(zhCompany.getResponse().getContentAsByteArray());
        assertThat(zhText).contains("三张报表").contains("利润表").contains("现金流量表")
                .contains("资产与负债");
        assertThat(zhText).contains("营业利润").contains("资产合计")
                .contains("负债和所有者权益合计");

        MvcResult zhBooks = mvc.perform(get("/api/runs/" + runId + "/debrief/export")
                        .param("format", "xlsx")
                        .param("locale", "zh")
                        .header("Authorization", "Bearer " + playerToken))
                .andExpect(status().isOk())
                .andReturn();
        try (XSSFWorkbook opened = new XSSFWorkbook(
                new ByteArrayInputStream(zhBooks.getResponse().getContentAsByteArray()))) {
            Sheet statements = opened.getSheet("报表");
            assertThat(statements).isNotNull();
            assertThat(statements.getRow(0).getCell(2).getStringCellValue()).isEqualTo("报表");
            assertThat(statements.getRow(0).getCell(4).getStringCellValue()).isEqualTo("项目");
        }
    }

    @Test
    void a_trainee_who_is_not_seated_is_refused() throws Exception {
        String strangerToken = login("two@acme.test");
        mvc.perform(get("/api/runs/" + runId + "/debrief/export")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    /** Every page's text, because the report now spreads its sections across pages (D46). */
    private static String allText(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        StringBuilder text = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            text.append(new PdfTextExtractor(reader).getTextFromPage(page)).append('\n');
        }
        return text.toString();
    }

    /** How many times a phrase appears: a table printed once, not once per round. */
    private static int occurrences(String text, String phrase) {
        return text.split(java.util.regex.Pattern.quote(phrase), -1).length - 1;
    }

    /**
     * D93: the print layout, measured. Every text run must sit inside its page — a figure printed off
     * the edge is the failure mode a reader notices and a text assertion never catches — and the
     * statements page must carry one column heading per round.
     */
    @Test
    void the_printed_statements_stay_inside_the_page() throws Exception {
        byte[] body = resultPdf(playerToken);
        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(body)) {
            assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(2);

            Positions positions = new Positions();
            positions.setSortByPosition(true);
            positions.getText(document);

            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                org.apache.pdfbox.pdmodel.PDPage sheet = document.getPage(page - 1);
                var box = sheet.getCropBox();
                float width = box.getWidth();
                float height = box.getHeight();
                if (sheet.getRotation() == 90 || sheet.getRotation() == 270) {
                    float swap = width;
                    width = height;
                    height = swap;
                }
                for (float[] run : positions.byPage.getOrDefault(page, List.of())) {
                    assertThat(run[0]).as("page " + page + " text starts on the page").isGreaterThanOrEqualTo(-1f);
                    assertThat(run[1]).as("page " + page + " text ends on the page").isLessThanOrEqualTo(width + 1f);
                    assertThat(run[2]).as("page " + page + " text is above the bottom edge").isGreaterThanOrEqualTo(-1f);
                    assertThat(run[3]).as("page " + page + " text is below the top edge").isLessThanOrEqualTo(height + 1f);
                }
            }

            // The statements section carries the round columns it claims: one per computed round.
            String text = allText(body);
            int computed = Integer.parseInt(text.replaceAll("(?s).*rounds 1\u2013(\\d+).*", "$1").trim());
            int columns = occurrences(text, "Round 1") + occurrences(text, "Round 2");
            assertThat(columns).as("each round is a column in the statements").isGreaterThanOrEqualTo(computed);
        }
    }

    /** Collects every text run's box, page by page, in the page's displayed orientation. */
    private static final class Positions extends org.apache.pdfbox.text.PDFTextStripper {

        private final java.util.Map<Integer, List<float[]>> byPage = new java.util.LinkedHashMap<>();
        private int page;

        private Positions() throws java.io.IOException {
            super();
        }

        @Override
        protected void startPage(org.apache.pdfbox.pdmodel.PDPage sheet) throws java.io.IOException {
            page++;
            byPage.put(page, new java.util.ArrayList<>());
            super.startPage(sheet);
        }

        @Override
        protected void writeString(String string, List<org.apache.pdfbox.text.TextPosition> runs)
                throws java.io.IOException {
            for (org.apache.pdfbox.text.TextPosition run : runs) {
                float x = run.getXDirAdj();
                float y = run.getYDirAdj();
                byPage.get(page).add(new float[]{x, x + run.getWidthDirAdj(), y, y + run.getHeightDir()});
            }
            super.writeString(string, runs);
        }
    }

    private byte[] resultPdf(String token) throws Exception {
        return mvc.perform(get("/api/runs/" + runId + "/debrief/export")
                        .param("format", "pdf")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private void openRound() throws Exception {
        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
    }

    private void compute() throws Exception {
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions submit(long teamId,
                                                                      Map<String, Object> body)
            throws Exception {
        return mvc.perform(post("/api/runs/" + runId + "/teams/" + teamId + "/decisions")
                .header("Authorization", "Bearer " + playerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private static Map<String, Object> payload(int p1, int p2, int p3, long marketing, int production) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("production", List.of(
                Map.of("product", "P1", "units", p1),
                Map.of("product", "P2", "units", p2),
                Map.of("product", "P3", "units", p3)));
        body.put("price", List.of(
                Map.of("product", "P1", "unitPrice", 1_000_000),
                Map.of("product", "P2", "unitPrice", 2_500_000),
                Map.of("product", "P3", "unitPrice", 5_000_000)));
        body.put("marketing", List.of(Map.of("channel", "DIGITAL", "spend", marketing)));
        body.put("manpower", Map.of("production", production));
        body.put("rnd", List.of(Map.of("project", "RND-P1-COST", "invest", 0)));
        body.put("capacityPurchase", 0);
        body.put("marketReport", false);
        return body;
    }

    private void addPlayer(long teamId, String email) throws Exception {
        mvc.perform(post("/api/runs/" + runId + "/players")
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "teamId", teamId, "userId", idOf(email), "roleKey", "OWNER"))))
                .andExpect(status().isCreated());
    }

    private void user(long tenantId, String email, String role) {
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?)
                """, tenantId, email, encoder.encode("pw-" + email), email, role);
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
