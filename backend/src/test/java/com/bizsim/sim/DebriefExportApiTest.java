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
 * D13: the debrief leaves the screen. A PDF that renders authored Chinese content (the class may be
 * Chinese) and a workbook the cohort can keep, both read from the stored results.
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
        facilitatorToken = login("trainer@acme.test");
        playerToken = login("one@acme.test");

        // A Chinese company name on purpose: the PDF must render authored content, not boxes.
        user(tenant, "admin@acme.test", "ADMIN");
        String adminToken = login("admin@acme.test");
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

        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitatorToken)).andExpect(status().isOk());
    }

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
                .contains("bizsim-debrief-run" + runId + "-round1.pdf");

        PdfReader reader = new PdfReader(body);
        String first = new PdfTextExtractor(reader).getTextFromPage(1);
        assertThat(first).contains("阿尔法制造");
        assertThat(first).contains("Beta Corp");
        // Six KPIs on the slide (D13) plus the revenue/cost/profit lines the class reads out.
        assertThat(first).contains("Revenue").contains("Profit").contains("Cash");
        assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(2);
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
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Company");
            assertThat(sheet.getRow(0).getCell(2).getStringCellValue()).isEqualTo("Revenue");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isIn("阿尔法制造", "Beta Corp");
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(workbook.getSheet("Cost lines")).isNotNull();
        }
    }

    @Test
    void only_the_staff_side_may_export() throws Exception {
        mvc.perform(get("/api/runs/" + runId + "/debrief/export")
                        .header("Authorization", "Bearer " + playerToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

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
