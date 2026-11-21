package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D114: a channel has a name per language, like a product (D87) — its code stays the single identity.
 *
 * <p>The defect this closes was reported from a Chinese class: the customer classes and the products
 * translated, and the three marketing channels stayed in English in the same card. The tests below hold
 * the three things that make the fix real: the shipped template carries the words, a frozen package copy
 * carries them too, and the player's context asks for them in the class's *own* language.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChannelNameTest extends PostgresTestBase {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Autowired
    ScenarioRepository scenarios;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void the_shipped_channels_carry_a_name_per_language() {
        Scenario scenario = scenarios.load("standard-v1");
        assertThat(scenario.channel("DIGITAL").orElseThrow().nameIn("en")).isEqualTo("Digital");
        assertThat(scenario.channel("DIGITAL").orElseThrow().nameIn("zh")).isEqualTo("数字营销");
        assertThat(scenario.channel("TRADE").orElseThrow().nameIn("zh")).isEqualTo("经销商渠道");
        assertThat(scenario.channel("FIELD").orElseThrow().nameIn("zh")).isEqualTo("直销团队");

        // A language with no row falls back to the authored name: a missing translation must read as the
        // author's words, never as a blank label or the raw code.
        assertThat(scenario.channel("TRADE").orElseThrow().nameIn("fr")).isEqualTo("Trade");
        assertThat(new Scenario.ChannelSpec("C", "Channel", BigDecimal.ONE, BigDecimal.ONE).nameIn("zh"))
                .isEqualTo("Channel");
    }

    @Test
    void a_frozen_copy_carries_the_channel_names() {
        Long tenantId = jdbc.queryForObject(
                "insert into tenant (code, name) values (?, ?) returning id", Long.class,
                "chn" + java.util.UUID.randomUUID().toString().substring(0, 6), "chn");
        String frozen = "pkg-chn-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        scenarios.cloneInto("standard-v1", frozen, "published", tenantId, "PUBLISHED");

        Scenario copy = scenarios.load(frozen);
        assertThat(copy.channel("TRADE").orElseThrow().nameIn("zh"))
                .as("a class played from the package reads the same words")
                .isEqualTo("经销商渠道");
    }

    /**
     * V43's seed, executed against the shape a package published before V42 really has: the same channels,
     * no `channel_text` rows. This is the defect that survived the first release — a Chinese class played
     * from an existing package still read the channels in English — so the migration is run here rather
     * than assumed to have worked.
     *
     * <p>A channel the trainer renamed is left alone: it is their word, not the shipped one, and a
     * translation nobody chose is worse than an untranslated name.
     */
    @Test
    void the_v43_seed_translates_a_package_frozen_before_it() throws Exception {
        Long tenantId = jdbc.queryForObject(
                "insert into tenant (code, name) values (?, ?) returning id", Long.class,
                "old" + java.util.UUID.randomUUID().toString().substring(0, 6), "old");
        String frozen = "pkg-old-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        scenarios.cloneInto("standard-v1", frozen, "published", tenantId, "PUBLISHED");
        // What a package frozen before V42 looks like: the channels exist, the words do not.
        jdbc.update("delete from channel_text where scenario_key = ?", frozen);
        // And a channel this trainer renamed: their word, which no seed may overwrite.
        jdbc.update("update channel set name = 'Our dealer network' where scenario_key = ? and code = 'TRADE'",
                frozen);

        StringBuilder bare = new StringBuilder();
        String sql = new org.springframework.core.io.ClassPathResource(
                "db/migration/V43__channel_text_copies.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        for (String line : sql.split("\\R")) {
            if (!line.trim().startsWith("--")) {
                bare.append(line).append('\n');
            }
        }
        for (String statement : bare.toString().split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }

        Scenario copy = scenarios.load(frozen);
        assertThat(copy.channel("DIGITAL").orElseThrow().nameIn("zh"))
                .as("an untouched shipped channel is translated").isEqualTo("数字营销");
        assertThat(copy.channel("FIELD").orElseThrow().nameIn("zh")).isEqualTo("直销团队");
        assertThat(copy.channel("TRADE").orElseThrow().nameIn("zh"))
                .as("a renamed channel keeps the trainer's words").isEqualTo("Our dealer network");
    }

    @Test
    void the_players_context_names_channels_in_the_class_language() throws Exception {
        long tenantId = jdbc.queryForObject(
                "insert into tenant (code, name) values ('acme-chn', 'Acme') returning id", Long.class);
        user(tenantId, "admin@chn.test", "ADMIN");
        user(tenantId, "trainer@chn.test", "FACILITATOR");
        user(tenantId, "one@chn.test", "PLAYER");
        String admin = login("acme-chn", "admin@chn.test");
        String player = login("acme-chn", "one@chn.test");

        long facilitatorId = jdbc.queryForObject(
                "select id from app_user where email = 'trainer@chn.test'", Long.class);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Cohort");
        body.put("scenarioKey", "standard-v1");
        body.put("teams", List.of(Map.of("name", "Alpha", "companyName", "Alpha Inc")));
        MvcResult created = mvc.perform(post("/api/runs")
                        .param("facilitatorId", String.valueOf(facilitatorId))
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode run = json.readTree(created.getResponse().getContentAsString());
        long runId = run.get("id").asLong();
        long teamId = run.get("teams").get(0).get("id").asLong();

        // The class language is the run's, set with the package (D84/V18). Write it where the console reads
        // it, then ask for the context the trainee's form is built from.
        jdbc.update("update run set lang = 'zh' where id = ?", runId);
        Long userId = jdbc.queryForObject("select id from app_user where email = 'one@chn.test'", Long.class);
        mvc.perform(post("/api/runs/" + runId + "/players")
                        .header("Authorization", "Bearer " + login("acme-chn", "trainer@chn.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "teamId", teamId, "userId", userId, "roleKey", "OWNER"))))
                .andExpect(status().isCreated());

        MvcResult context = mvc.perform(get("/api/runs/" + runId + "/teams/" + teamId + "/context")
                        .header("Authorization", "Bearer " + player))
                .andExpect(status().isOk())
                .andReturn();
        // Explicitly UTF-8: MockHttpServletResponse defaults to ISO-8859-1 when the body carries no
        // charset, which turned 数字营销 into mojibake in the assertion (the body itself was correct).
        JsonNode channels = json.readTree(
                context.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("channels");

        Map<String, String> names = new LinkedHashMap<>();
        channels.forEach(channel -> names.put(channel.get("code").asText(), channel.get("name").asText()));
        assertThat(names).containsEntry("DIGITAL", "数字营销")
                .containsEntry("TRADE", "经销商渠道")
                .containsEntry("FIELD", "直销团队");
    }

    // ------------------------------------------------------------------ helpers

    private void user(long tenantId, String email, String role) {
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?)
                """, tenantId, email, encoder.encode("pw-" + email), email, role);
    }

    private String login(String tenantCode, String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", tenantCode, "email", email,
                                "password", "pw-" + email))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
