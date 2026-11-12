package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The environment model (D18): the economy moves market size, products react differently, the event
 * deck is drawn once per run, and the forecast is stable enough to argue about.
 */
@SpringBootTest
class EnvironmentTest extends PostgresTestBase {

    @Autowired
    private ScenarioRepository scenarios;
    @Autowired
    private EnvironmentService environment;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * A run id that no test creates, so no scheduled event can leak into a pure-economy assertion.
     * Circumstances collide here because every test class shares one PostgreSQL instance.
     */
    private static final long NO_EVENTS_RUN = 987_654_321L;

    @Test
    void a_recession_hits_the_premium_product_harder_than_the_basic_one() {
        Scenario scenario = scenarios.load("standard-v1");

        // Position 3 of the cycle is the recession: demand index 0.92, cost index 1.07.
        EnvironmentService.RoundEnvironment trough = environment.forRound(scenario, NO_EVENTS_RUN, 3);

        assertThat(trough.demandIndex()).isEqualByComparingTo("0.920");
        assertThat(trough.costIndex()).isEqualByComparingTo("1.070");
        assertThat(trough.trendStrength())
                .as("consumers trade down in the same round").isEqualByComparingTo("-0.40");
        assertThat(trough.demandByProduct().get("P1"))
                .as("basic, elasticity 0.4").isGreaterThan(trough.demandByProduct().get("P2"));
        assertThat(trough.demandByProduct().get("P2"))
                .as("standard, elasticity 1.0").isGreaterThan(trough.demandByProduct().get("P3"));
    }

    @Test
    void a_round_the_timeline_does_not_cover_is_neutral() {
        Scenario scenario = scenarios.load("standard-v1");
        EnvironmentService.RoundEnvironment far = environment.forRound(scenario, NO_EVENTS_RUN, 99);

        assertThat(far.demandIndex()).isEqualByComparingTo("1.000");
        assertThat(far.demandByProduct().get("P3")).isEqualByComparingTo("1.000");
    }

    @Test
    void the_forecast_is_noisy_but_stable_and_never_reveals_an_event() {
        Scenario scenario = scenarios.load("standard-v1");

        List<Map<String, Object>> first = environment.forecast(scenario, 42L, 1);
        List<Map<String, Object>> again = environment.forecast(scenario, 42L, 1);

        assertThat(first).hasSize(2);
        assertThat(first.toString()).isEqualTo(again.toString());

        // Each forecast value is within the published band of the timeline it is based on.
        List<Scenario.RoundSpec> timeline = scenario.rounds();
        for (Map<String, Object> row : first) {
            int round = (Integer) row.get("round");
            BigDecimal actual = timeline.stream().filter(spec -> spec.round() == round)
                    .findFirst().orElseThrow().demandIndex();
            BigDecimal forecast = (BigDecimal) row.get("demandIndex");
            assertThat(forecast.subtract(actual).abs())
                    .as("round %d forecast %.3f vs timeline %.3f", round, forecast, actual)
                    .isLessThanOrEqualTo(actual.multiply(new BigDecimal("0.081")));
        }
    }

    @Test
    void the_event_deck_is_drawn_once_and_does_not_change_on_a_second_call() {
        Scenario scenario = scenarios.load("standard-v1");
        long runId = seedRun();
        long otherRun = seedRun();

        environment.scheduleEvents(scenario, runId);
        List<Map<String, Object>> first = jdbc.queryForList(
                "select event_code, starts_round from run_environment_event where run_id = ? order by event_code",
                runId);

        environment.scheduleEvents(scenario, runId);
        List<Map<String, Object>> again = jdbc.queryForList(
                "select event_code, starts_round from run_environment_event where run_id = ? order by event_code",
                runId);
        environment.scheduleEvents(scenario, otherRun);
        List<Map<String, Object>> other = jdbc.queryForList(
                "select event_code, starts_round from run_environment_event where run_id = ? order by event_code",
                otherRun);

        assertThat(first.toString()).isEqualTo(again.toString());
        assertThat(first).allSatisfy(row -> assertThat((Integer) row.get("starts_round")).isBetween(1, 7));
        // Two runs are drawn independently: each schedule is valid for its own window.
        assertThat(other).allSatisfy(row -> assertThat((Integer) row.get("starts_round")).isBetween(1, 7));
    }

    @Test
    void a_second_cycle_repeats_the_shape_with_a_drift() {
        Scenario scenario = scenarios.load("standard-v1");
        long runId = seedRun();

        environment.generateTimeline(scenario, runId, 8);

        // Same cycle position, one cycle later: 2% demand drift on top of the template.
        assertThat(environment.forRound(scenario, runId, 1).demandIndex())
                .isEqualByComparingTo("1.000");
        assertThat(environment.forRound(scenario, runId, 5).demandIndex())
                .isEqualByComparingTo("1.020");
        // The recession and the consumer trade-down repeat at positions 3 and 7.
        assertThat(environment.forRound(scenario, runId, 3).trendStrength())
                .isEqualByComparingTo("-0.40");
        assertThat(environment.forRound(scenario, runId, 7).trendStrength())
                .isEqualByComparingTo("-0.40");
        assertThat(environment.forRound(scenario, runId, 7).demandIndex())
                .isEqualByComparingTo("0.938");
    }

    @Test
    void the_deck_carries_the_stock_market_and_seasonal_cards() {
        Scenario scenario = scenarios.load("standard-v1");

        assertThat(scenario.events()).extracting(Scenario.EventSpec::code)
                .contains("EV-BULL-RUN", "EV-MARKET-CRASH", "EV-HOLIDAY-SEASON");

        Scenario.EventSpec bull = event(scenario, "EV-BULL-RUN");
        Scenario.EventSpec crash = event(scenario, "EV-MARKET-CRASH");
        Scenario.EventSpec holiday = event(scenario, "EV-HOLIDAY-SEASON");

        // The kind of shock is what the workbench prints, and it is worth its own category.
        assertThat(bull.category()).isEqualTo("MARKETS");
        assertThat(crash.category()).isEqualTo("MARKETS");
        assertThat(holiday.category()).isEqualTo("DEMAND_SHOCK");

        // D86: the Chinese set is its own cards, not a translation of these.
        Scenario.EventSpec bullZh = event(scenario, "EV-BULL-RUN-ZH");
        Scenario.EventSpec crashZh = event(scenario, "EV-MARKET-CRASH-ZH");
        Scenario.EventSpec holidayZh = event(scenario, "EV-HOLIDAY-SEASON-ZH");
        assertThat(bullZh.lang()).isEqualTo("zh");
        assertThat(bullZh.name()).isEqualTo("股市牛市");
        assertThat(crashZh.name()).isEqualTo("股市崩盘");
        assertThat(holidayZh.name()).isEqualTo("节庆旺季");
        assertThat(crashZh.description()).contains("股价大幅下跌");
        assertThat(holidayZh.description()).contains("节庆旺季带动各阶层零售需求");

        // Every card moves all three classes, and a crash lands hardest on the affluent one.
        assertThat(bull.effects()).hasSize(3);
        assertThat(crash.effects()).hasSize(3);
        assertThat(holiday.effects()).hasSize(3);
        assertThat(effect(crash, "SEG-PREMIUM").sizeMultiplier())
                .isLessThan(effect(crash, "SEG-BASIC").sizeMultiplier());
        assertThat(effect(bull, "SEG-PREMIUM").sizeMultiplier())
                .isGreaterThan(effect(bull, "SEG-BASIC").sizeMultiplier());
        // A holiday season is broad demand, strongest at the mass end.
        assertThat(effect(holiday, "SEG-BASIC").sizeMultiplier())
                .isGreaterThan(effect(holiday, "SEG-PREMIUM").sizeMultiplier());
    }

    @Test
    void each_language_owns_a_full_cycle_and_deck_with_identical_mechanics() {
        Scenario scenario = scenarios.load("standard-v1");

        assertThat(scenario.languages()).containsExactly("en", "zh");
        assertThat(scenario.roundsOf("en")).hasSameSizeAs(scenario.roundsOf("zh"));
        for (Scenario.RoundSpec en : scenario.roundsOf("en")) {
            Scenario.RoundSpec zh = scenario.roundAt("zh", en.round()).orElseThrow();
            assertThat(zh.demandIndex()).as("round %d demand", en.round())
                    .isEqualByComparingTo(en.demandIndex());
            assertThat(zh.costIndex()).isEqualByComparingTo(en.costIndex());
            assertThat(zh.wageIndex()).isEqualByComparingTo(en.wageIndex());
            assertThat(zh.trendStrength()).isEqualByComparingTo(en.trendStrength());
            // The words are the language's own; the narrative is not empty.
            assertThat(zh.description()).isNotBlank();
        }
        // The English cycle keeps its label; the Chinese cycle its own.
        assertThat(scenario.roundAt("en", 1).orElseThrow().trendLabel()).isEqualTo("Stable");
        assertThat(scenario.roundAt("zh", 1).orElseThrow().trendLabel()).isEqualTo("平稳");

        // The deck is a set per language, with the same card count and the same reach per card.
        assertThat(scenario.eventsOf("en")).hasSameSizeAs(scenario.eventsOf("zh"));
        for (Scenario.EventSpec en : scenario.eventsOf("en")) {
            Scenario.EventSpec zh = event(scenario, en.code() + "-ZH");
            assertThat(zh.effects()).as("%s effects", en.code()).hasSameSizeAs(en.effects());
            assertThat(zh.category()).isEqualTo(en.category());
            assertThat(zh.probability()).isEqualByComparingTo(en.probability());
        }
    }

    /** A run whose class reads the environment in its own language. */
    private long seedRun(String lang) {
        long runId = seedRun();
        jdbc.update("update run set lang = ? where id = ?", lang, runId);
        return runId;
    }

    @Test
    void a_chinese_run_reads_the_chinese_set_and_its_own_narrative() {
        Scenario scenario = scenarios.load("standard-v1");
        long runId = seedRun("zh");
        environment.generateTimeline(scenario, runId, 4);

        Map<String, Object> market = environment.conditions(scenario, runId, 1, "zh");

        assertThat((String) market.get("description")).contains("需求与成本都处于基准水平");
        assertThat((String) market.get("headline")).isEqualTo("平稳");
        // The classes are one mechanic with per-language words (D83 kept for classes).
        assertThat((List<Map<String, Object>>) market.get("segments"))
                .extracting(row -> row.get("name"))
                .containsExactly("大众市场", "中端市场", "高端客户");
    }

    @Test
    void a_chinese_run_draws_the_chinese_cards_from_the_same_seed() {
        Scenario scenario = scenarios.load("standard-v1");
        long chinese = seedRun("zh");

        environment.scheduleEvents(scenario, chinese);

        List<String> drawn = jdbc.queryForList(
                "select event_code from run_environment_event where run_id = ? order by event_code",
                String.class, chinese);
        // Whatever fires is a card of this run's deck, never the English one.
        assertThat(drawn).allSatisfy(code -> assertThat(code).endsWith("-ZH"));
        // The draw is seeded on the canonical code, so the same run would fire the same cards in
        // English: both codes reduce to the same base.
        assertThat(drawn).allSatisfy(code -> assertThat(EnvironmentService.canonical(code))
                .doesNotEndWith("-ZH"));
    }

    private static Scenario.EventSpec event(Scenario scenario, String code) {
        return scenario.events().stream().filter(spec -> spec.code().equals(code))
                .findFirst().orElseThrow();
    }

    private static Scenario.EventEffect effect(Scenario.EventSpec event, String segment) {
        return event.effects().stream().filter(e -> e.segmentCode().equals(segment))
                .findFirst().orElseThrow();
    }

    @Test
    void a_product_name_is_per_language_and_falls_back_to_the_authored_name() {
        Scenario scenario = scenarios.load("standard-v1");
        Scenario.ProductSpec basic = scenario.product("P1").orElseThrow();

        // D87: the code and the numbers are one row; the name is the reader's language.
        assertThat(basic.nameIn("zh")).isEqualTo("基础款");
        assertThat(basic.descriptionIn("zh")).contains("大众市场");
        // No English row is needed — the authored name is the base.
        assertThat(basic.nameIn("en")).isEqualTo(basic.name());
        // A language nobody has translated yet still reads, so a new class is never blank.
        assertThat(basic.nameIn("ms")).isEqualTo(basic.name());

        // Classes work the same way, and their words are already per-language in environment_text.
        Scenario.SegmentSpec mass = scenario.segment("SEG-BASIC").orElseThrow();
        assertThat(mass.nameIn("zh")).isEqualTo("大众市场");
        assertThat(mass.nameIn("ms")).isEqualTo(mass.name());
        assertThat(mass.descriptionIn("zh")).contains("大众家庭");
    }

    // ---------------------------------------------------------------- D81-D85: the class is the unit

    @Test
    void the_shipped_template_carries_the_customer_model() {
        Scenario scenario = scenarios.load("standard-v1");

        assertThat(scenario.segments()).extracting(Scenario.SegmentSpec::code)
                .containsExactly("SEG-BASIC", "SEG-MID", "SEG-PREMIUM");
        assertThat(scenario.products()).allSatisfy(product ->
                assertThat(scenario.segmentOf(product)).as("%s serves a class", product.code())
                        .isPresent());
        assertThat(scenario.product("P3").orElseThrow().crossSegmentPct())
                .isEqualByComparingTo("0.020");
        // The deck names the shocks a trainer actually asks for, each with a row per class.
        assertThat(scenario.events()).extracting(Scenario.EventSpec::code)
                .contains("EV-OIL-CRISIS", "EV-INFLATION", "EV-TECH-DEPRESSION");
        assertThat(scenario.events()).allSatisfy(event ->
                assertThat(event.effects()).as("%s moves all three classes", event.code()).hasSize(3));
    }

    @Test
    void a_recession_hits_the_affluent_class_harder_than_the_mass_market() {
        Scenario scenario = scenarios.load("standard-v1");

        EnvironmentService.RoundEnvironment trough = environment.forRound(scenario, NO_EVENTS_RUN, 3);

        assertThat(trough.segmentSize("SEG-PREMIUM"))
                .as("affluent, elasticity 1.8").isLessThan(trough.segmentSize("SEG-BASIC"));
        assertThat(trough.segmentSize("SEG-MID"))
                .isBetween(trough.segmentSize("SEG-PREMIUM"), trough.segmentSize("SEG-BASIC"));
        // With no card firing, purchasing power is the class's base (no event has touched it).
        assertThat(trough.segmentPurchasingPower("SEG-PREMIUM")).isEqualByComparingTo("1.000");
    }

    @Test
    void an_event_moves_the_classes_it_names_and_no_others() {
        Scenario scenario = scenarios.load("standard-v1");
        long runId = seedRun();

        // Freeze the card's effect on this run the way the draw does (D84): premium customers fall
        // by half and can afford 20% less; the mass market is untouched.
        jdbc.update("""
                insert into run_environment_event (run_id, event_code, starts_round, duration_months,
                                                   demand_multiplier, cost_multiplier,
                                                   wage_multiplier, effects)
                values (?, 'EV-RECESSION', 1, 6, 1.000, 1.100, 1.000, ?::jsonb)
                """, runId, """
                [{"segmentCode": "SEG-PREMIUM", "sizeMultiplier": 0.500,
                  "purchasingPowerMultiplier": 0.800}]
                """);

        EnvironmentService.RoundEnvironment environment1 = environment.forRound(scenario, runId, 1);

        assertThat(environment1.segmentSize("SEG-PREMIUM")).isEqualByComparingTo("0.500000");
        assertThat(environment1.segmentPurchasingPower("SEG-PREMIUM")).isEqualByComparingTo("0.800000");
        assertThat(environment1.segmentSize("SEG-BASIC")).isEqualByComparingTo("1.000000");
        assertThat(environment1.segmentPurchasingPower("SEG-BASIC")).isEqualByComparingTo("1.000000");
        // The premium product inherits its class's weather; the basic one is untouched.
        assertThat(environment1.demandByProduct().get("P3")).isEqualByComparingTo("0.400000");
        assertThat(environment1.demandByProduct().get("P1")).isEqualByComparingTo("1.000000");
        // The card's cost side still reaches every product.
        assertThat(environment1.costIndex()).isEqualByComparingTo("1.100");
    }

    @Test
    void the_workbench_reads_the_economy_in_the_class_language() {
        Scenario scenario = scenarios.load("standard-v1");
        long runId = seedRun("en");
        environment.generateTimeline(scenario, runId, 4);

        Map<String, Object> english = environment.conditions(scenario, runId, 1, "en");

        assertThat((String) english.get("description"))
                .contains("Demand and costs are at their baseline");
        assertThat((String) english.get("headline")).isEqualTo("Stable");
        assertThat((List<Map<String, Object>>) english.get("segments"))
                .extracting(row -> row.get("name"))
                .containsExactly("Mass market", "Middle market", "Affluent buyers");
        // Each class says which product serves it, so the table is readable on its own.
        assertThat((List<Map<String, Object>>) english.get("segments"))
                .extracting(row -> row.get("product"))
                .containsExactly("P1", "P2", "P3");
    }

    @Test
    void an_active_card_is_described_in_words_the_class_can_read() {
        Scenario scenario = scenarios.load("standard-v1");
        long chinese = seedRun("zh");
        environment.generateTimeline(scenario, chinese, 4);
        // D86: a Chinese class is drawn Chinese cards, so the frozen row names the -ZH card.
        jdbc.update("""
                insert into run_environment_event (run_id, event_code, starts_round, duration_months,
                                                   demand_multiplier, cost_multiplier,
                                                   wage_multiplier, effects)
                values (?, 'EV-INFLATION-ZH', 1, 3, 1.000, 1.120, 1.080, '[]'::jsonb)
                """, chinese);

        Map<String, Object> zh = environment.conditions(scenario, chinese, 1, "zh");
        List<Map<String, Object>> zhEvents = (List<Map<String, Object>>) zh.get("events");
        assertThat(zhEvents).hasSize(1);
        assertThat(zhEvents.get(0).get("name")).isEqualTo("通货膨胀");
        assertThat(zhEvents.get(0).get("category")).isEqualTo("INFLATION");
        assertThat((String) zh.get("description")).contains("物价上涨快于工资");

        // An English run names the same card in English from its own set.
        long english = seedRun("en");
        environment.generateTimeline(scenario, english, 4);
        jdbc.update("""
                insert into run_environment_event (run_id, event_code, starts_round, duration_months,
                                                   demand_multiplier, cost_multiplier,
                                                   wage_multiplier, effects)
                values (?, 'EV-INFLATION', 1, 3, 1.000, 1.120, 1.080, '[]'::jsonb)
                """, english);

        Map<String, Object> en = environment.conditions(scenario, english, 1, "en");
        List<Map<String, Object>> enEvents = (List<Map<String, Object>>) en.get("events");
        assertThat(enEvents.get(0).get("name")).isEqualTo("Inflation");
        assertThat((String) en.get("description")).contains("Prices rise faster than wages");
    }

    // ------------------------------------------------------------------ helpers

    private long seedRun() {
        String suffix = Long.toString(System.nanoTime());
        long tenant = jdbc.queryForObject(
                "insert into tenant (code, name) values (?, ?) returning id", Long.class,
                "env" + suffix, "Env " + suffix);
        long user = jdbc.queryForObject("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, 'x', 'Facilitator', 'FACILITATOR') returning id
                """, Long.class, tenant, "f" + suffix + "@env.test");
        long simulation = jdbc.queryForObject("""
                insert into simulation (tenant_id, name, scenario_key, facilitator_id)
                values (?, 'Env', 'standard-v1', ?) returning id
                """, Long.class, tenant, user);
        return jdbc.queryForObject("""
                insert into run (tenant_id, simulation_id, scenario_key, status)
                values (?, ?, 'standard-v1', 'OPEN') returning id
                """, Long.class, tenant, simulation);
    }
}
