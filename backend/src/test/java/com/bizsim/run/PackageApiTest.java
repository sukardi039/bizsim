package com.bizsim.run;

import com.bizsim.support.PostgresTestBase;
import com.fasterxml.jackson.databind.JsonNode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Building and running are two jobs (ruling 3 Nov 2026).
 *
 * <p>A **builder** publishes a package — a template plus the companies that will compete — and a
 * **facilitator** starts sessions from it without being able to change what is inside. The tests pin
 * both halves of that promise: the permissions, and the immutability (a package records the template
 * version it was built on, so a later edit cannot reach back into a class already sold to it).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PackageApiTest extends PostgresTestBase {

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
    private String otherFacilitator;
    private String player;
    private long profileId;

    @BeforeEach
    void seed() throws Exception {
        for (String table : List.of("round_statement", "round_result", "decision", "round", "player",
                "company_product", "company_staff", "company_rnd", "team", "run", "simulation")) {
            jdbc.update("delete from " + table);
        }
        // scenario.frozen_for_package -> package, and package.template_key -> scenario: a cycle, so
        // it is broken explicitly before either side is deleted.
        jdbc.update("update scenario set frozen_for_package = null");
        jdbc.update("delete from simulation_package");
        jdbc.update("delete from scenario where key like 'pkg-%'");
        for (String table : List.of("company_profile", "app_user", "tenant")) {
            jdbc.update("delete from " + table);
        }
        jdbc.update("""
                insert into company_profile (tenant_id, name, company_name, products, notes)
                values (null, 'Standard manufacturer', '', '[]'::jsonb, 'shipped')
                """);

        acme = tenant("acme");
        other = tenant("other");
        user(acme, "builder@acme.test", "BUILDER");
        user(acme, "trainer@acme.test", "FACILITATOR");
        user(acme, "ada@acme.test", "PLAYER");
        user(other, "trainer@other.test", "FACILITATOR");
        builder = login("acme", "builder@acme.test");
        facilitator = login("acme", "trainer@acme.test");
        otherFacilitator = login("other", "trainer@other.test");
        player = login("acme", "ada@acme.test");

        profileId = jdbc.queryForObject("""
                insert into company_profile (tenant_id, name, company_name, products)
                values (?, 'Alpha Industries', 'Alpha Industries', '["P1","P2"]'::jsonb) returning id
                """, Long.class, acme);
    }

    @Test
    void a_builder_publishes_and_a_facilitator_consumes() throws Exception {
        long packageId = publish("Autumn 2026");

        // The builder's package is visible to the facilitator, who cannot mint one.
        mvc.perform(get("/api/packages").header("Authorization", "Bearer " + facilitator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Autumn 2026"))
                .andExpect(jsonPath("$[0].templateVersion").value(1));
        mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "locale", "en", "name", "Mine", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(profileId)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.builder_only"));

        // ...and the builder cannot run one.
        mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Nope"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.facilitator_only"));
    }

    @Test
    void a_session_is_started_from_the_package_and_records_it() throws Exception {
        long packageId = publish("Autumn 2026");
        long ada = jdbc.queryForObject("select id from app_user where email = 'ada@acme.test'", Long.class);

        MvcResult started = mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Monday class", "traineeIds", List.of(ada)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.teams.length()").value(1))
                .andExpect(jsonPath("$.players.length()").value(1))
                .andReturn();

        long runId = json.readTree(started.getResponse().getContentAsString()).get("id").asLong();
        long teamId = json.readTree(started.getResponse().getContentAsString())
                .get("teams").get(0).get("id").asLong();

        assertThat(jdbc.queryForObject("select company_name from team where id = ?", String.class, teamId))
                .isEqualTo("Alpha Industries");
        assertThat(jdbc.queryForObject("select package_id from run where id = ?", Long.class, runId))
                .isEqualTo(packageId);
        assertThat(jdbc.queryForList(
                "select product_code from company_product where team_id = ? order by product_code",
                String.class, teamId)).containsExactly("P1", "P2");

        // The seated trainee lands on her company.
        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamId + "/context")
                        .header("Authorization", "Bearer " + player))
                .andExpect(status().isOk());
    }

    @Test
    void a_publish_that_is_refused_leaves_no_template_behind() throws Exception {
        int before = jdbc.queryForObject("select count(*) from scenario", Integer.class);
        long beta = jdbc.queryForObject("""
                insert into company_profile (tenant_id, name, company_name, products)
                values (?, 'Beta Corp', 'Beta Corp', '[]'::jsonb) returning id
                """, Long.class, acme);

        // Two companies, one seat: refused after the point where the old code had already frozen a
        // template copy, which then showed up in the template library attached to nothing (D54).
        mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "locale", "en", "name", "Broken", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(profileId, beta),
                                "traineeIds", List.of(1L)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("package.seats_mismatch"));

        assertThat(jdbc.queryForObject("select count(*) from scenario", Integer.class)).isEqualTo(before);
        assertThat(jdbc.queryForList("select key from scenario where key like 'pkg-%'", String.class))
                .isEmpty();
    }

    @Test
    void a_package_can_hold_several_parts_and_they_play_in_order() throws Exception {
        String second = copyTemplate();
        int templatesBefore = jdbc.queryForObject("select count(*) from scenario where "
                + "frozen_for_package is null", Integer.class);

        MvcResult published = mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "locale", "en", "name", "Course", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(profileId),
                                "traineeIds", java.util.Arrays.asList(
                                        jdbc.queryForObject("select id from app_user "
                                                + "where email = 'ada@acme.test'", Long.class)),
                                "parts", List.of(
                                        Map.of("name", "Basics", "templateKey", "standard-v1"),
                                        Map.of("name", "Competition", "templateKey", second))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parts.length()").value(2))
                .andExpect(jsonPath("$.parts[0].position").value(1))
                .andExpect(jsonPath("$.parts[1].name").value("Competition"))
                .andReturn();
        long packageId = json.readTree(published.getResponse().getContentAsString()).get("id").asLong();

        // Each part froze its own copy, and neither appears in the template library (D14/D54).
        assertThat(jdbc.queryForObject("select count(*) from scenario where frozen_for_package is null",
                Integer.class)).isEqualTo(templatesBefore);

        // Part 2 before part 1 is refused, with the part named.
        mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Too early", "part", 2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("part.previous_missing"));

        long firstRun = startPart(packageId, 1);
        playAll(firstRun);
        assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, firstRun))
                .isEqualTo("FINISHED");

        long secondRun = startPart(packageId, 2);
        assertThat(jdbc.queryForObject("select part_position from run where id = ?", Integer.class,
                secondRun)).isEqualTo(2);
        // Same class list, seated again in the new part.
        assertThat(jdbc.queryForObject("select count(*) from team where run_id = ?", Integer.class,
                secondRun)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from player where run_id = ?", Integer.class,
                secondRun)).isEqualTo(1);

        // The comparison sees both parts and the move between them.
        mvc.perform(get("/api/packages/" + packageId + "/comparison")
                        .header("Authorization", "Bearer " + facilitator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parts.length()").value(2))
                .andExpect(jsonPath("$.companies.length()").value(1))
                .andExpect(jsonPath("$.companies[0].parts.length()").value(2))
                .andExpect(jsonPath("$.companies[0].parts[0].delta").doesNotExist())
                .andExpect(jsonPath("$.companies[0].parts[1].delta").exists());

        // Resetting the programme puts every part back to month 0.
        mvc.perform(post("/api/packages/" + packageId + "/reset")
                        .header("Authorization", "Bearer " + facilitator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partsReset").value(2));
        assertThat(jdbc.queryForObject("select count(*) from round where run_id in (?, ?)",
                Integer.class, firstRun, secondRun)).isZero();
    }

    /**
     * A part is started once. Replaying a class means resetting the session it belongs to or
     * publishing a new package, not opening a second run of the same part — which the console could
     * not distinguish from the first and a facilitator would have to guess between.
     */
    @Test
    void a_part_that_has_started_is_offered_no_second_time() throws Exception {
        long packageId = publish("Played once");

        // Nothing has begun, so the package offers part 1.
        mvc.perform(get("/api/packages").header("Authorization", "Bearer " + facilitator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].startedParts.length()").value(0));

        startPart(packageId, 1);

        // The package now reports the part as started: the console leaves it out of the choice.
        mvc.perform(get("/api/packages").header("Authorization", "Bearer " + facilitator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].startedParts[0]").value(1));

        // And the server refuses it whatever the client sends, so a duplicate cannot be created by hand.
        mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Again", "part", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("part.already_started"));
        assertThat(jdbc.queryForObject("select count(*) from run where package_id = ?",
                Integer.class, packageId)).isEqualTo(1);
    }

    private long startPart(long packageId, int part) throws Exception {
        MvcResult session = mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Course", "part", part))))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(session.getResponse().getContentAsString()).get("id").asLong();
    }

    /** Plays a part to the end: open, compute, open, compute… */
    private void playAll(long runId) throws Exception {
        boolean first = true;
        while (true) {
            mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                    .header("Authorization", "Bearer " + facilitator)).andExpect(status().isOk());
            mvc.perform(post("/api/runs/" + runId + "/rounds/close")
                    .header("Authorization", "Bearer " + facilitator)).andExpect(status().isOk());
            String status = jdbc.queryForObject("select status from run where id = ?", String.class, runId);
            if ("FINISHED".equals(status)) {
                return;
            }
            if (!first && !"OPEN".equals(status)) {
                return;
            }
            first = false;
        }
    }

    @Test
    void the_class_list_is_prepared_with_the_package() throws Exception {
        long beta = jdbc.queryForObject("""
                insert into company_profile (tenant_id, name, company_name, products)
                values (?, 'Beta Corp', 'Beta Corp', '[]'::jsonb) returning id
                """, Long.class, acme);
        Long ada = jdbc.queryForObject("select id from app_user where email = 'ada@acme.test'", Long.class);

        MvcResult published = mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "locale", "en", "name", "Autumn 2026", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(profileId, beta),
                                "traineeIds", java.util.Arrays.asList(ada, null)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seats.length()").value(1))
                .andExpect(jsonPath("$.seats[0].position").value(0))
                .andExpect(jsonPath("$.seats[0].email").value("ada@acme.test"))
                .andReturn();
        long packageId = json.readTree(published.getResponse().getContentAsString()).get("id").asLong();

        // The facilitator starts the session and the prepared class is seated: nobody is ticked here.
        MvcResult started = mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Monday class"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.players.length()").value(1))
                .andReturn();
        long runId = json.readTree(started.getResponse().getContentAsString()).get("id").asLong();
        long firstTeam = json.readTree(started.getResponse().getContentAsString())
                .get("teams").get(0).get("id").asLong();
        Long seatedTeam = jdbc.queryForObject(
                "select team_id from player where run_id = ? and user_id = ?", Long.class, runId, ada);
        assertThat(seatedTeam).isEqualTo(firstTeam);

        // The seated trainee must be able to see their own session: the console asks GET /runs and
        // without this a player sat on "No simulation yet" forever behind a 403.
        String adaToken = login("acme", "ada@acme.test");
        mvc.perform(get("/api/runs").header("Authorization", "Bearer " + adaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value((int) runId));
    }

    @Test
    void the_class_language_travels_from_the_package_to_the_session() throws Exception {
        MvcResult published = mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Chinese cohort", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(profileId),
                                "traineeIds", List.of(jdbc.queryForObject(
                                        "select id from app_user where email = 'ada@acme.test'",
                                        Long.class)),
                                "locale", "zh"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.locale").value("zh"))
                .andReturn();
        long packageId = json.readTree(published.getResponse().getContentAsString()).get("id").asLong();

        // A trainee lists their own run and finds the class language on it, so the console can follow
        // it without the person having to set anything.
        mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Monday class"))))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/runs").header("Authorization", "Bearer " + login("acme", "ada@acme.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locale").value("zh"));

        // D86: the language is set before the timeline and the deck are frozen, so round 1 already
        // reads the Chinese cycle — the narrative, the label and the deck all come from that set.
        long runId = jdbc.queryForObject("select max(id) from run", Long.class);
        long teamId = jdbc.queryForObject("select id from team where run_id = ? order by id limit 1",
                Long.class, runId);
        mvc.perform(post("/api/runs/" + runId + "/rounds/next")
                        .header("Authorization", "Bearer " + facilitator))
                .andExpect(status().isOk());
        mvc.perform(get("/api/runs/" + runId + "/teams/" + teamId + "/context")
                        .header("Authorization", "Bearer " + facilitator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market.headline").value("平稳"))
                .andExpect(jsonPath("$.market.description").value(
                        org.hamcrest.Matchers.containsString("需求与成本都处于基准水平")));
        assertThat(jdbc.queryForList(
                "select event_code from run_environment_event where run_id = ?", String.class, runId))
                .allSatisfy(code -> assertThat(code).endsWith("-ZH"));

        // D66: no language declared is refused — the class language is part of the package, because it
        // decides both the interface and which R&D catalogue the class competes on.
        mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Plain cohort", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(profileId)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("package.locale_required"));

        // Anything that is not a two-letter code is refused rather than stored and half-honoured
        // (D86: a real two-letter language is data, so 'fr' would now be accepted).
        mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Bad language", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(profileId),
                                "locale", "fra"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("package.locale_invalid"));
    }

    @Test
    void a_malformed_class_list_is_refused() throws Exception {
        long beta = jdbc.queryForObject("""
                insert into company_profile (tenant_id, name, company_name, products)
                values (?, 'Beta Corp', 'Beta Corp', '[]'::jsonb) returning id
                """, Long.class, acme);
        Long ada = jdbc.queryForObject("select id from app_user where email = 'ada@acme.test'", Long.class);
        Long trainer = jdbc.queryForObject(
                "select id from app_user where email = 'trainer@acme.test'", Long.class);

        // One trainee per company, or none.
        mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "locale", "en", "name", "Bad", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(profileId, beta),
                                "traineeIds", List.of(ada)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("package.seats_mismatch"));

        // One person cannot run two competing companies.
        mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "locale", "en", "name", "Bad", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(profileId, beta),
                                "traineeIds", List.of(ada, ada)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("package.trainee_twice"));

        // Only an active trainee account can be seated.
        mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "locale", "en", "name", "Bad", "templateKey", "standard-v1",
                                "companyProfileIds", List.of(profileId),
                                "traineeIds", List.of(trainer)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("user.not_found"));
    }

    @Test
    void publishing_freezes_a_copy_so_a_later_edit_cannot_reach_the_class() throws Exception {
        // A tenant draft the builder can edit, published as a package.
        String draft = copyTemplate();
        long packageId = publishFrom(draft, "Frozen package");
        String frozenKey = jdbc.queryForObject(
                "select template_key from simulation_package where id = ?", String.class, packageId);
        assertThat(frozenKey).startsWith("pkg-");
        assertThat(jdbc.queryForObject("select source_template_key from simulation_package where id = ?",
                String.class, packageId)).isEqualTo(draft);

        // The builder edits the draft afterwards: 8 rounds become 6, catalogue untouched.
        mvc.perform(put("/api/templates/" + draft + "/settings")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("rounds", 6))))
                .andExpect(status().isOk());

        // The class runs on the frozen copy: it still has the eight rounds it was published with.
        long ada = jdbc.queryForObject("select id from app_user where email = 'ada@acme.test'", Long.class);
        MvcResult started = mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Monday class", "traineeIds", List.of(ada)))))
                .andExpect(status().isCreated())
                .andReturn();
        long runId = json.readTree(started.getResponse().getContentAsString()).get("id").asLong();

        assertThat(jdbc.queryForObject("select scenario_key from run where id = ?", String.class, runId))
                .isEqualTo(frozenKey);
        assertThat(jdbc.queryForObject("select definition->>'rounds' from scenario where key = ?",
                String.class, frozenKey)).isEqualTo("8");
        assertThat(jdbc.queryForObject("select definition->>'rounds' from scenario where key = ?",
                String.class, draft)).isEqualTo("6");

        // The frozen copy is not a template a builder can pick or edit.
        assertThat(jdbc.queryForObject("""
                select count(*) from scenario s join simulation_package p on p.id = s.frozen_for_package
                where s.key = ?
                """, Integer.class, frozenKey)).isEqualTo(1);
        mvc.perform(put("/api/templates/" + frozenKey + "/settings")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("rounds", 3))))
                .andExpect(status().isNotFound());

        // Re-publishing the edited draft makes a *new* package on a *new* frozen copy.
        long second = publishFrom(draft, "Second cohort");
        String secondFrozen = jdbc.queryForObject(
                "select template_key from simulation_package where id = ?", String.class, second);
        assertThat(secondFrozen).isNotEqualTo(frozenKey);
        assertThat(jdbc.queryForObject("select definition->>'rounds' from scenario where key = ?",
                String.class, secondFrozen)).isEqualTo("6");
    }

    @Test
    void a_retired_package_cannot_start_a_session() throws Exception {
        long packageId = publish("Old cohort");

        mvc.perform(post("/api/packages/" + packageId + "/retire")
                        .header("Authorization", "Bearer " + builder))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Late class"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("package.retired"));

        assertThat(jdbc.queryForObject("select count(*) from run", Integer.class)).isZero();
    }

    @Test
    void another_tenants_package_is_invisible() throws Exception {
        long packageId = publish("Autumn 2026");

        mvc.perform(post("/api/packages/" + packageId + "/sessions")
                        .header("Authorization", "Bearer " + otherFacilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Not mine"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("package.not_found"));
        mvc.perform(get("/api/packages").header("Authorization", "Bearer " + otherFacilitator))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void tidy_up_removes_retired_packages_that_never_ran_a_session() throws Exception {
        long abandoned = publish("Abandoned draft");
        mvc.perform(post("/api/packages/" + abandoned + "/retire")
                        .header("Authorization", "Bearer " + builder))
                .andExpect(status().isNoContent());
        String abandonedFrozen = jdbc.queryForObject(
                "select template_key from simulation_package where id = ?", String.class, abandoned);

        long used = publish("Ran a class");
        mvc.perform(post("/api/packages/" + used + "/sessions")
                        .header("Authorization", "Bearer " + facilitator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "A class"))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/packages/" + used + "/retire")
                        .header("Authorization", "Bearer " + builder))
                .andExpect(status().isNoContent());

        long live = publish("Still published");

        // Only the abandoned one is offered: a package that ran a class is history, and a published
        // one is not retired at all.
        mvc.perform(get("/api/packages/tidy-up").header("Authorization", "Bearer " + builder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value((int) abandoned));

        // A facilitator cannot collect anything.
        mvc.perform(post("/api/packages/tidy-up").header("Authorization", "Bearer " + facilitator))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.builder_only"));

        mvc.perform(post("/api/packages/tidy-up").header("Authorization", "Bearer " + builder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(1))
                .andExpect(jsonPath("$.names[0]").value("Abandoned draft"));

        // The frozen copy and its catalogue went with it.
        assertThat(jdbc.queryForObject("select count(*) from scenario where key = ?",
                Integer.class, abandonedFrozen)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from product where scenario_key = ?",
                Integer.class, abandonedFrozen)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from simulation_package where id = ?",
                Integer.class, abandoned)).isZero();

        // The used and the live packages survive.
        assertThat(jdbc.queryForObject("select count(*) from simulation_package where id in (?, ?)",
                Integer.class, used, live)).isEqualTo(2);

        // ...and a second pass is a no-op, not an error.
        mvc.perform(post("/api/packages/tidy-up").header("Authorization", "Bearer " + builder))
                .andExpect(jsonPath("$.removed").value(0));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A programme has one frozen copy per part (D62). Tidy up used to delete only the package's own
     * copy, so every extra part left a copy behind with no back-reference — visible afterwards in the
     * library and in the package template picker, named "… · Part B (vN)".
     */
    @Test
    void tidy_up_removes_every_frozen_copy_of_a_multi_part_package() throws Exception {
        String draft = copyTemplate();
        MvcResult published = mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "locale", "en", "name", "Two-part programme", "templateKey", draft,
                                "companyProfileIds", List.of(profileId),
                                "parts", List.of(
                                        Map.of("name", "Part A", "templateKey", draft),
                                        Map.of("name", "Part B", "templateKey", draft))))))
                .andExpect(status().isCreated())
                .andReturn();
        long id = json.readTree(published.getResponse().getContentAsString()).get("id").asLong();

        List<String> copies = jdbc.queryForList(
                "select key from scenario where frozen_for_package = ? order by key", String.class, id);
        assertThat(copies).as("one frozen copy per part").hasSize(2);

        mvc.perform(post("/api/packages/" + id + "/retire")
                        .header("Authorization", "Bearer " + builder))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/packages/tidy-up")
                        .header("Authorization", "Bearer " + builder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(1));

        for (String key : copies) {
            assertThat(jdbc.queryForObject("select count(*) from scenario where key = ?",
                    Integer.class, key)).as("copy %s removed", key).isZero();
        }
        // The library is what the picker reads: nothing of the programme is left in it.
        mvc.perform(get("/api/templates").header("Authorization", "Bearer " + builder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    /**
     * Belt and braces for copies already orphaned by the old tidy up: the library recognises the
     * reserved frozen key shape even when the back-reference to a package is gone.
     */
    @Test
    void a_frozen_copy_without_its_package_is_not_offered_as_a_template() throws Exception {
        jdbc.update("""
                insert into scenario (key, name, definition, version)
                values ('pkg-acme-deadbeef', 'Ghost programme · Part B (v6)', '{"rounds":6}'::jsonb, 6)
                """);

        // Only the shipped template is left in the library: the inserted copy is not offered.
        mvc.perform(get("/api/templates").header("Authorization", "Bearer " + builder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].key").value("standard-v1"));
    }

    private long publish(String name) throws Exception {
        return publishFrom("standard-v1", name);
    }

    private long publishFrom(String templateKey, String name) throws Exception {
        MvcResult published = mvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + builder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "locale", "en", "name", name, "templateKey", templateKey,
                                "companyProfileIds", List.of(profileId)))))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(published.getResponse().getContentAsString()).get("id").asLong();
    }

    private String copyTemplate() throws Exception {
        MvcResult copied = mvc.perform(post("/api/templates/standard-v1/copy")
                        .header("Authorization", "Bearer " + builder))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = json.readTree(copied.getResponse().getContentAsString());
        return body.get("key").asText();
    }

    private long tenant(String code) {
        return jdbc.queryForObject("insert into tenant (code, name) values (?, ?) returning id",
                Long.class, code, code);
    }

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
                                "tenantCode", tenantCode, "email", email, "password", "pw-" + email))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
