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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D89: a decision may only name the products the company carries.
 *
 * <p>A company profile can seat a company with a subset of the catalogue. Before this rule the
 * decision form offered the whole catalogue, the validator accepted it, the engine produced and sold
 * the product — and its closing stock was silently lost, because {@code company_product} had no row
 * for it. The statement then reported stock that the next round never saw, which is the revaluation
 * line the three reports had to show.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CompanyProductLineTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private InitDataService initData;

    private long runId;
    private long fullCompany;
    private long narrowCompany;
    private String trainer;
    private String narrowPlayer;

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
    void seed() throws Exception {
        clean();
        jdbc.update("""
                insert into company_profile (tenant_id, name, company_name, products, notes)
                values (null, 'Standard manufacturer', '', '[]'::jsonb, 'shipped')
                """);

        InitDataService.Summary summary = initData.init("line",
                new InitDataService.Params(2, 2, 0, null, "en", "line-pass-1", null, null, null));
        runId = summary.runId();
        List<Long> teams = jdbc.queryForList("select id from team where run_id = ? order by id",
                Long.class, runId);
        assertThat(teams).hasSize(2);
        fullCompany = teams.get(0);
        narrowCompany = teams.get(1);

        // The state a subset profile leaves behind: this company carries P1 and P2 only.
        jdbc.update("delete from company_product where team_id = ? and product_code = 'P3'",
                narrowCompany);

        trainer = login("line", "trainer@line.test");
        narrowPlayer = login("line", "player2@line.test");
    }

    @Test
    void the_form_offers_only_the_companys_own_line() throws Exception {
        JsonNode context = call("GET", "/api/runs/" + runId + "/teams/" + narrowCompany + "/context",
                narrowPlayer);
        List<String> codes = new ArrayList<>();
        context.get("products").forEach(product -> codes.add(product.get("code").asText()));
        assertThat(codes).containsExactly("P1", "P2");

        JsonNode full = call("GET", "/api/runs/" + runId + "/teams/" + fullCompany + "/context", trainer);
        List<String> fullCodes = new ArrayList<>();
        full.get("products").forEach(product -> fullCodes.add(product.get("code").asText()));
        assertThat(fullCodes).containsExactly("P1", "P2", "P3");
    }

    @Test
    void a_decision_naming_a_product_the_company_does_not_carry_is_refused() throws Exception {
        openRound();
        JsonNode context = call("GET", "/api/runs/" + runId + "/teams/" + narrowCompany + "/context",
                narrowPlayer);

        // A client that names the whole catalogue: the third product exists in the template, but this
        // company was seated without it.
        JsonNode full = call("GET", "/api/runs/" + runId + "/teams/" + fullCompany + "/context", trainer);
        Map<String, Object> payload = decision(full, List.of("P1", "P2", "P3"), Map.of("P3", 5));
        MvcResult result = mvc.perform(post("/api/runs/" + runId + "/teams/" + narrowCompany + "/decisions")
                        .header("Authorization", "Bearer " + narrowPlayer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        List<String> codes = new ArrayList<>();
        body.get("problems").forEach(problem -> codes.add(problem.get("code").asText()));
        assertThat(codes).contains("production.not_carried");
        assertThat(codes).doesNotContain("production.missing_product");

        // The same payload without the product it does not hold is accepted.
        Map<String, Object> accepted = decision(context, List.of("P1", "P2"), Map.of());
        mvc.perform(post("/api/runs/" + runId + "/teams/" + narrowCompany + "/decisions")
                        .header("Authorization", "Bearer " + narrowPlayer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(accepted)))
                .andExpect(status().isOk());
    }

    @Test
    void a_company_with_two_products_keeps_its_stock_and_never_revalues() throws Exception {
        openRound();
        JsonNode context = call("GET", "/api/runs/" + runId + "/teams/" + narrowCompany + "/context",
                narrowPlayer);
        // Enough to produce stock that the round does not sell: the closing units must carry forward.
        Map<String, Object> payload = decision(context, List.of("P1", "P2"), Map.of("P1", 900, "P2", 60));
        submit(payload, narrowPlayer, narrowCompany);

        call("POST", "/api/runs/" + runId + "/rounds/close", trainer);
        // A second round, decided on the statement the first one left behind.
        openRound();
        JsonNode next = call("GET", "/api/runs/" + runId + "/teams/" + narrowCompany + "/context",
                narrowPlayer);
        assertThat(stockOf(next, "P1")).as("closing stock carried into the next round")
                .isGreaterThan(java.math.BigDecimal.ZERO);
        submit(decision(next, List.of("P1", "P2"), Map.of()), narrowPlayer, narrowCompany);
        call("POST", "/api/runs/" + runId + "/rounds/close", trainer);

        // Every statement of this company carries its own two products and nothing else.
        JsonNode statements = call("GET", "/api/runs/" + runId + "/teams/" + narrowCompany + "/statements",
                narrowPlayer);
        assertThat(statements.size()).isEqualTo(2);
        for (JsonNode statement : statements) {
            List<String> codes = new ArrayList<>();
            statement.get("detail").get("products").forEach(line -> codes.add(line.get("product").asText()));
            assertThat(codes).containsExactly("P1", "P2");
        }

        // Cash and profit tie against a stock value that never had to be revalued.
        JsonNode books = call("GET", "/api/runs/" + runId + "/teams/" + narrowCompany + "/financials",
                narrowPlayer);
        for (JsonNode round : books.get("rounds")) {
            boolean revaluation = false;
            for (JsonNode row : round.get("balance")) {
                revaluation |= "balance.revaluation".equals(row.get("key").asText());
            }
            assertThat(revaluation).as("no stock value went missing between rounds").isFalse();
        }
    }

    @Test
    void the_workbook_lists_only_the_products_the_company_carries() throws Exception {
        openRound();
        JsonNode context = call("GET", "/api/runs/" + runId + "/teams/" + narrowCompany + "/context",
                narrowPlayer);
        submit(decision(context, List.of("P1", "P2"), Map.of()), narrowPlayer, narrowCompany);
        call("POST", "/api/runs/" + runId + "/rounds/close", trainer);

        MvcResult result = mvc.perform(get("/api/runs/" + runId + "/debrief/export")
                        .param("format", "xlsx")
                        .header("Authorization", "Bearer " + narrowPlayer))
                .andExpect(status().isOk())
                .andReturn();

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                             new java.io.ByteArrayInputStream(
                                     result.getResponse().getContentAsByteArray()))) {
            List<String> headers = new ArrayList<>();
            workbook.getSheet("Submissions").getRow(0)
                    .forEach(cell -> headers.add(cell.getStringCellValue()));
            assertThat(headers).contains("P1 units", "P2 units");
            assertThat(headers).doesNotContain("P3 units", "P3 price", "P3 quality");
            assertThat(workbook.getSheet("Statements")).as("D88 travels in the workbook").isNotNull();
        }
    }

    // ------------------------------------------------------------------ helpers

    private void openRound() throws Exception {
        // Round 1 is already open after the seed; the call is tolerated so it works for later rounds.
        MvcResult result = mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                        .header("Authorization", "Bearer " + trainer))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isIn(200, 409);
    }

    private void submit(Map<String, Object> payload, String token, long team) throws Exception {
        mvc.perform(post("/api/runs/" + runId + "/teams/" + team + "/decisions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andExpect(status().isOk());
    }

    /** A decision in the console's shape for the named products. */
    private static Map<String, Object> decision(JsonNode context, List<String> codes,
                                                Map<String, Integer> units) {
        List<Map<String, Object>> production = new ArrayList<>();
        List<Map<String, Object>> price = new ArrayList<>();
        for (JsonNode product : context.get("products")) {
            String code = product.get("code").asText();
            if (!codes.contains(code)) {
                continue;
            }
            production.add(Map.of("product", code, "units", units.getOrDefault(code, 100)));
            price.add(Map.of("product", code, "unitPrice", product.get("price").decimalValue()));
        }
        List<Map<String, Object>> rnd = new ArrayList<>();
        context.get("rndProjects").forEach(project ->
                rnd.add(Map.of("project", project.get("code").asText(), "invest", 0)));

        Map<String, Object> body = new HashMap<>();
        body.put("production", production);
        body.put("price", price);
        body.put("marketing", List.of());
        body.put("manpower", Map.of("production", 30));
        body.put("rnd", rnd);
        body.put("capacityPurchase", 0);
        body.put("marketReport", false);
        return body;
    }

    private static java.math.BigDecimal stockOf(JsonNode context, String code) {
        for (JsonNode product : context.get("products")) {
            if (code.equals(product.get("code").asText())) {
                return product.get("stock").decimalValue();
            }
        }
        throw new IllegalStateException("no product " + code);
    }

    private JsonNode call(String method, String path, String token) throws Exception {
        var request = method.equals("POST") ? post(path) : get(path);
        if (method.equals("POST")) {
            request.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        MvcResult result = mvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }

    private String login(String tenant, String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", tenant, "email", email, "password", "line" + "-pass-1"))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
