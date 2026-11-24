package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D103: a shared map is a link the class can open without an account, and it must say exactly what the
 * console says — refusing to draw rather than drawing the wrong thing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MapShareApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private long acme;
    private long other;
    private String builder;
    private String facilitator;
    private String player;
    private String otherFacilitator;
    private long profileAlpha;
    private long profileBeta;

    @BeforeEach
    void seed() throws Exception {
        for (String table : List.of("map_share", "round_statement", "round_result", "decision", "round",
                "player", "company_product", "company_staff", "company_rnd", "team", "run", "simulation")) {
            jdbc.update("delete from " + table);
        }
        jdbc.update("update scenario set frozen_for_package = null");
        jdbc.update("delete from simulation_package");
        jdbc.update("delete from scenario where key like 'pkg-%'");
        for (String table : List.of("company_profile", "app_user", "tenant")) {
            jdbc.update("delete from " + table);
        }

        acme = tenant("acme");
        other = tenant("other");
        user(acme, "builder@acme.test", "BUILDER");
        user(acme, "trainer@acme.test", "FACILITATOR");
        user(acme, "ada@acme.test", "PLAYER");
        user(acme, "bob@acme.test", "PLAYER");
        user(other, "trainer@other.test", "FACILITATOR");

        builder = login("acme", "builder@acme.test");
        facilitator = login("acme", "trainer@acme.test");
        player = login("acme", "ada@acme.test");
        otherFacilitator = login("other", "trainer@other.test");

        profileAlpha = jdbc.queryForObject("""
                insert into company_profile (tenant_id, name, company_name, products)
                values (?, 'Alpha Industries', 'Alpha Industries', '["P1","P2"]'::jsonb) returning id
                """, Long.class, acme);
        profileBeta = jdbc.queryForObject("""
                insert into company_profile (tenant_id, name, company_name, products)
                values (?, 'Beta Works', 'Beta Works', '["P1","P2"]'::jsonb) returning id
                """, Long.class, acme);
    }

    @Test
    void a_facilitator_shares_a_map_and_the_class_opens_it_without_a_token() throws Exception {
        Played played = playedClass();

        MvcResult shared = mvc.perform(post("/api/runs/" + played.runId() + "/map-share")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Where we are\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").exists())
                .andExpect(jsonPath("$.title").value("Where we are"))
                .andReturn();
        String token = tokenOf(shared);
        assertThat(token).as("32 random bytes, hex").hasSize(64);

        // The link itself: no Authorization header, which is what a trainee's phone sends.
        MvcResult page = mvc.perform(get("/share/map/" + token)).andExpect(status().isOk()).andReturn();
        String html = page.getResponse().getContentAsString();
        assertThat(page.getResponse().getContentType()).contains("text/html");
        assertThat(html).as("the figure is drawn").contains("<svg", "position-dot");
        assertThat(html).as("names substituted").contains("Alpha Industries", "Beta Works");
        assertThat(html).as("no placeholder survived").doesNotContain("{{name").doesNotContain("__NUM__");
        // A section per computed round, each with its caption, and no leftover placeholder anywhere.
        assertThat(html).as("a section per computed round").contains("<section>", "<h2>");
        assertThat(html).as("the company names appear in the picture").contains(">Alpha Industries<");

        assertThat(html).as("not for search engines").contains("noindex");

        // The token is stored hashed: reading the table must not hand anyone a working link.
        String stored = jdbc.queryForObject("select token_hash from map_share order by id desc limit 1",
                String.class);
        assertThat(stored).isNotEqualTo(token).hasSize(64);
    }

    @Test
    void a_withdrawn_link_is_a_404_not_a_login_prompt() throws Exception {
        Played played = playedClass();
        String token = tokenOf(mvc.perform(post("/api/runs/" + played.runId() + "/map-share")
                        .header("Authorization", "Bearer " + facilitator))
                .andExpect(status().isOk()).andReturn());
        long id = jdbc.queryForObject("select max(id) from map_share", Long.class);

        mvc.perform(post("/api/runs/" + played.runId() + "/map-share/" + id + "/revoke")
                        .header("Authorization", "Bearer " + facilitator))
                .andExpect(status().isOk());

        MvcResult gone = mvc.perform(get("/share/map/" + token))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(gone.getResponse().getContentAsString()).contains("not available");
        assertThat(jdbc.queryForObject("select revoked_at is not null from map_share where id = ?",
                Boolean.class, id)).as("the row survives: the record that it was shared is history").isTrue();
    }

    @Test
    void sharing_needs_a_session_and_a_run_you_may_read() throws Exception {
        Played played = playedClass();

        mvc.perform(post("/api/runs/" + played.runId() + "/map-share"))
                .andExpect(status().isUnauthorized());

        // The trainee of this run may share what the class was shown.
        mvc.perform(post("/api/runs/" + played.runId() + "/map-share")
                        .header("Authorization", "Bearer " + player))
                .andExpect(status().isOk());

        // A facilitator from another tenant cannot even see the run.
        mvc.perform(post("/api/runs/" + played.runId() + "/map-share")
                        .header("Authorization", "Bearer " + otherFacilitator))
                .andExpect(status().isNotFound());
    }

    @Test
    void a_run_with_nothing_computed_has_no_map_to_share() throws Exception {
        long packageId = publish();
        MvcResult started = mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Empty"))))
                .andExpect(status().isCreated()).andReturn();
        long runId = json.readTree(started.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/runs/" + runId + "/map-share")
                        .header("Authorization", "Bearer " + facilitator))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("map.no_rounds"));
    }

    @Test
    void the_template_is_found_wherever_the_build_put_it() {
        // A plain backend build has no template (the console produces it, the image copies it in), so this
        // test supplies one the way the image does: on the classpath, from the console's own build when it
        // is present. It asserts the *lookup*, not the presence — the presence is asserted by the image
        // build itself (`unzip -l ... | grep map-template.json`), where it can actually be guaranteed.
        assertThat(testTemplateShips())
                .as("a template is reachable, or the app must answer 503 rather than draw nothing")
                .isTrue();
        PositionMapPage page = new PositionMapPage("");
        // D119: the template is built from limits.json, so it carries one slot per seat at the ceiling
        // (classSize.max = 30). It used to read 6 when the ceiling was 6.
        assertThat(page.slots()).as("one slot per seat at the class-size ceiling").isEqualTo(30);
        assertThat(page.text(page.template(), "zh", "debrief.quadrantStuck")).isEqualTo("卡在中间");
        assertThat(page.text(page.template(), "en", "debrief.positionBrand")).isEqualTo("Brand index");
    }

    // ------------------------------------------------------------------ helpers

    /** True when a template can be found from the classpath or the working directory, as the app looks. */
    private boolean testTemplateShips() {
        return PositionMapPage.class.getClassLoader().getResource("map-template.json") != null
                || java.nio.file.Files.isRegularFile(java.nio.file.Path.of("map-template.json"))
                || java.nio.file.Files.isRegularFile(java.nio.file.Path.of("target/classes/map-template.json"));
    }

    private record Played(long runId) {
    }

    /**
     * Two companies, one computed round, built through the same services the console uses — so the map
     * reads real statements rather than fixtures written straight into the tables.
     */
    private Played playedClass() throws Exception {
        long packageId = publish();
        List<Long> trainees = List.of(
                jdbc.queryForObject("select id from app_user where email = 'ada@acme.test'", Long.class),
                jdbc.queryForObject("select id from app_user where email = 'bob@acme.test'", Long.class));
        MvcResult started = mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Monday class", "traineeIds", trainees))))
                .andExpect(status().isCreated()).andReturn();
        long runId = json.readTree(started.getResponse().getContentAsString()).get("id").asLong();

        // One round, played from the two companies' own decisions: the map reads statements, so the
        // statements have to be the engine's, not ones written here.
        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                .header("Authorization", "Bearer " + facilitator)).andExpect(status().isOk());
        List<Long> teams = jdbc.queryForList("select id from team where run_id = ? order by id", Long.class, runId);
        double[] posture = {0.85, 1.15};
        for (int i = 0; i < teams.size(); i++) {
            long teamId = teams.get(i);
            String seat = i == 0 ? player : login("acme", "bob@acme.test");
            Map<String, Object> context = contextOf(runId, teamId, seat);
            submit(runId, teamId, seat, posture[i], context);
        }
        mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                .header("Authorization", "Bearer " + facilitator)).andExpect(status().isOk());
        return new Played(runId);
    }

    private Map<String, Object> contextOf(long runId, long teamId, String token) throws Exception {
        MvcResult context = mvc.perform(get("/api/runs/" + runId + "/teams/" + teamId + "/context")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return json.readValue(context.getResponse().getContentAsString(), Map.class);
    }

    /** A plain month at the given price posture, produced from the company's own context. */
    private void submit(long runId, long teamId, String token, double posture, Map<String, Object> context)
            throws Exception {
        List<Map<String, Object>> products = (List<Map<String, Object>>) context.get("products");
        List<Map<String, Object>> prices = new java.util.ArrayList<>();
        List<Map<String, Object>> production = new java.util.ArrayList<>();
        for (Map<String, Object> product : products) {
            double price = ((Number) product.get("price")).doubleValue();
            prices.add(Map.of("product", product.get("code"), "unitPrice", Math.round(price * posture * 100) / 100.0));
            // A tenth of what ten workers can make: a real round, and no company can run dry.
            double units = Math.floor(((Number) product.get("unitsPerWorker")).doubleValue());
            production.add(Map.of("product", product.get("code"), "units", units));
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("production", production);
        payload.put("price", prices);
        payload.put("marketing", List.of(Map.of("channel", "TRADE", "spend", 5_000_000)));
        payload.put("manpower", Map.of("production", 10));
        payload.put("rnd", List.of());
        payload.put("quality", List.of());
        payload.put("capacityPurchase", 0);
        payload.put("marketReport", false);
        MvcResult result = mvc.perform(post("/api/runs/" + runId + "/teams/" + teamId + "/decisions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("decision refused: %s", result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private String tokenOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([0-9a-f]+)\".*", "$1");
    }

    private long publish() throws Exception {
        MvcResult published = mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "locale", "en", "name", "Autumn", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(profileAlpha, profileBeta)))))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(published.getResponse().getContentAsString()).get("id").asLong();
    }

    private long tenant(String code) {
        return jdbc.queryForObject(
                "insert into tenant (code, name) values (?, ?) returning id", Long.class, code, code);
    }

    private void user(long tenantId, String email, String role) {
        jdbc.update("""
                insert into app_user (tenant_id, email, display_name, role, password_hash)
                values (?, ?, ?, ?, ?)
                """, tenantId, email, email, role, encoder.encode("password1234"));
    }

    private String login(String tenant, String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", tenant, "email", email, "password", "password1234"))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
