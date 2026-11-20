package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D107: channel reach can be weighted per customer class.
 *
 * <p>The property that makes this safe to ship is stated as a test rather than an intention: a template
 * with **no** segment weights must behave exactly as it did before, and one with weights must actually
 * move share between classes. Both are driven through the engine, because that is where the arithmetic is.
 */
@SpringBootTest
class SegmentChannelTest extends PostgresTestBase {

    @Autowired
    ScenarioRepository scenarios;

    @Autowired
    ScoringEngine engine;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * D111: the shipped template now states its market reach, so the segment-blind path is proved on a
     * template that genuinely has no weights — the shipped one, stripped. That keeps the guarantee
     * honest: "no weights means the pre-D107 arithmetic" must hold for *any* such template, not just for
     * the one we happened to ship before.
     */
    @Test
    void a_template_without_segment_weights_is_unchanged() {
        Scenario base = withoutSegmentWeights(scenarios.load("standard-v1"));
        assertThat(base.channels()).allMatch(c -> !c.segmentSpecific());
        assertThat(base.channel("TRADE").orElseThrow().reachFor("SEG-BASIC"))
                .as("no stated weight falls back to the channel's own")
                .isEqualByComparingTo(base.channel("TRADE").orElseThrow().reachWeight());

        List<String> codes = base.products().stream().map(Scenario.ProductSpec::code).toList();
        EnvironmentService.RoundEnvironment env = EnvironmentService.RoundEnvironment.neutral(1, codes);
        List<ScoringEngine.TeamInput> teams = List.of(
                team(base, 1, Map.of("TRADE", new BigDecimal("30000000"))),
                team(base, 2, Map.of("FIELD", new BigDecimal("30000000"))));

        ScoringEngine.RoundResult result = engine.compute(base, env, teams);
        // Same total spend, different channels, no class weighting: the channel with the higher reach
        // weight still wins, and the split is the one the old formula gives.
        assertThat(demandOf(result, 1, "P1")).isGreaterThan(demandOf(result, 2, "P1"));
    }

    /**
     * With weights, a channel can be strong in one class and weak in another — so the two companies'
     * advantage **reverses** between classes on the same product. That is the whole point of the feature.
     */
    @Test
    void a_weighted_template_lets_a_channel_win_one_class_and_lose_another() {
        Scenario base = scenarios.load("standard-v1");
        // Trade reaches the mass market well and the premium barely; Field the reverse. Every other
        // number is the shipped template's, so the only variable is the class weighting.
        Map<String, BigDecimal> trade = Map.of("SEG-BASIC", new BigDecimal("1.600"), "SEG-PREMIUM", new BigDecimal("0.200"));
        Map<String, BigDecimal> field = Map.of("SEG-BASIC", new BigDecimal("0.200"), "SEG-PREMIUM", new BigDecimal("1.600"));
        List<Scenario.ChannelSpec> channels = new ArrayList<>();
        for (Scenario.ChannelSpec channel : base.channels()) {
            channels.add(switch (channel.code()) {
                case "TRADE" -> new Scenario.ChannelSpec(channel.code(), channel.name(),
                        channel.reachWeight(), channel.brandAlpha(), trade);
                case "FIELD" -> new Scenario.ChannelSpec(channel.code(), channel.name(),
                        channel.reachWeight(), channel.brandAlpha(), field);
                default -> channel;
            });
        }
        Scenario weighted = new Scenario(base.key(), base.params(), base.products(), channels,
                base.projects(), base.rounds(), base.segments(), base.events());
        assertThat(weighted.channels()).anyMatch(Scenario.ChannelSpec::segmentSpecific);

        List<String> codes = weighted.products().stream().map(Scenario.ProductSpec::code).toList();
        EnvironmentService.RoundEnvironment env = EnvironmentService.RoundEnvironment.neutral(1, codes);
        // Company 1 advertises in Trade, company 2 in Field, same money. Both carry every product.
        List<ScoringEngine.TeamInput> teams = List.of(
                team(weighted, 1, Map.of("TRADE", new BigDecimal("40000000"))),
                team(weighted, 2, Map.of("FIELD", new BigDecimal("40000000"))));

        ScoringEngine.RoundResult result = engine.compute(weighted, env, teams);

        // P1 is made for the mass market (SEG-BASIC) and P3 for the premium one, so the same two
        // companies should trade places between them.
        assertThat(demandOf(result, 1, "P1"))
                .as("Trade reaches the mass market better, so the Trade advertiser wins P1")
                .isGreaterThan(demandOf(result, 2, "P1"));
        assertThat(demandOf(result, 2, "P3"))
                .as("Field reaches the premium class better, so the Field advertiser wins P3")
                .isGreaterThan(demandOf(result, 1, "P3"));
    }

    /** Whatever the weights, the class markets still add up to the product's whole market (D105/D106). */
    @Test
    void weighted_classes_still_add_up_to_the_whole_market() {
        Scenario base = scenarios.load("standard-v1");
        Map<String, BigDecimal> uneven = Map.of("SEG-BASIC", new BigDecimal("1.500"),
                "SEG-MID", new BigDecimal("0.400"), "SEG-PREMIUM", new BigDecimal("0.100"));
        List<Scenario.ChannelSpec> channels = new ArrayList<>();
        for (Scenario.ChannelSpec channel : base.channels()) {
            channels.add(new Scenario.ChannelSpec(channel.code(), channel.name(),
                    channel.reachWeight(), channel.brandAlpha(), uneven));
        }
        Scenario weighted = new Scenario(base.key(), base.params(), base.products(), channels,
                base.projects(), base.rounds(), base.segments(), base.events());
        List<String> codes = weighted.products().stream().map(Scenario.ProductSpec::code).toList();
        EnvironmentService.RoundEnvironment env = EnvironmentService.RoundEnvironment.neutral(1, codes);
        List<ScoringEngine.TeamInput> teams = List.of(
                team(weighted, 1, Map.of("DIGITAL", new BigDecimal("20000000"))),
                team(weighted, 2, Map.of("TRADE", new BigDecimal("20000000"))),
                team(weighted, 3, Map.of("FIELD", new BigDecimal("20000000"))));

        ScoringEngine.RoundResult result = engine.compute(weighted, env, teams);

        for (String code : codes) {
            BigDecimal served = engine.servedMarket(weighted, env, code, teams.size());
            BigDecimal handed = BigDecimal.ZERO;
            for (ScoringEngine.TeamOutcome outcome : result.teams()) {
                for (ScoringEngine.ProductLine line : outcome.lines()) {
                    if (line.product().equals(code)) {
                        assertThat(line.demand().stripTrailingZeros().scale())
                                .as("%s demand is whole", code).isLessThanOrEqualTo(0);
                        handed = handed.add(line.demand());
                    }
                }
            }
            assertThat(handed).as("%s: handed %s of a %s-item market", code, handed, served)
                    .isEqualByComparingTo(served);
        }
    }

    /**
     * A published package freezes its own copy of the template. The per-class weights must travel with
     * it — a class played from the package would otherwise fall back to segment-blind marketing, and
     * nothing in the console would say why. Found by auditing a weighted run: the frozen copy had no
     * `channel_reach` rows at all, so the first "weighted" audit was really a second unweighted one.
     */
    @Test
    void a_frozen_copy_carries_the_per_class_weights() {
        // A tenant draft, because `scenario.tenant_id` is a real foreign key — the clone must belong to
        // somebody. This is the one thing the test needs that the engine tests do not.
        Long tenantId = jdbc.queryForObject(
                "insert into tenant (code, name) values (?, ?) returning id", Long.class,
                "seg" + java.util.UUID.randomUUID().toString().substring(0, 6), "seg");
        String frozen = "pkg-test-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String draft = "draft-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        scenarios.cloneInto("standard-v1", draft, "draft with weights", tenantId, "DRAFT");
        List<ScenarioRepository.ChannelRow> rows = new java.util.ArrayList<>(scenarios.channels(draft));
        rows.replaceAll(row -> "TRADE".equals(row.code())
                ? new ScenarioRepository.ChannelRow(row.code(), row.name(), row.reachWeight(),
                        row.brandAlpha(), Map.of("SEG-BASIC", new BigDecimal("1.800")))
                : row);
        scenarios.saveChannels(draft, rows);

        // V9 redefined scenario.status as DRAFT | PUBLISHED; a frozen copy of a published template is
        // PUBLISHED. (The V1 check with READY/ARCHIVED was replaced by V9 — read the latest, not the first.)
        scenarios.cloneInto(draft, frozen, "published", tenantId, "PUBLISHED");

        Scenario copy = scenarios.load(frozen);
        assertThat(copy.channel("TRADE").orElseThrow().reachFor("SEG-BASIC"))
                .as("the frozen copy keeps the weight")
                .isEqualByComparingTo("1.800");
        assertThat(copy.channel("TRADE").orElseThrow().reachFor("SEG-PREMIUM"))
                .as("a class with no stated weight falls back to the channel's own")
                .isEqualByComparingTo(copy.channel("TRADE").orElseThrow().reachWeight());
    }

    // ------------------------------------------------------------------ helpers

    /** The same scenario with every channel's market-specific weights removed — the pre-D111 economy. */
    private static Scenario withoutSegmentWeights(Scenario base) {
        List<Scenario.ChannelSpec> channels = base.channels().stream()
                .map(c -> new Scenario.ChannelSpec(c.code(), c.name(), c.reachWeight(), c.brandAlpha()))
                .toList();
        return new Scenario(base.key(), base.params(), base.products(), channels, base.projects(),
                base.rounds(), base.segments(), base.events());
    }

    private BigDecimal demandOf(ScoringEngine.RoundResult result, long teamId, String product) {
        return result.teams().stream().filter(t -> t.teamId() == teamId).findFirst().orElseThrow()
                .lines().stream().filter(l -> l.product().equals(product)).findFirst().orElseThrow()
                .demand();
    }

    private ScoringEngine.TeamInput team(Scenario scenario, long id, Map<String, BigDecimal> spend) {
        Map<String, ScoringEngine.ProductState> products = new LinkedHashMap<>();
        for (Scenario.ProductSpec spec : scenario.products()) {
            products.put(spec.code(), new ScoringEngine.ProductState(spec.code(),
                    spec.basePrice(), spec.baseQuality(), spec.baseUnitCost(),
                    BigDecimal.ZERO, BigDecimal.ZERO));
        }
        Map<String, Integer> staff = new LinkedHashMap<>();
        staff.put("PRODUCTION", 60);
        staff.put("SALES", 3);
        staff.put("RND", 2);
        staff.put("ADMIN", 2);
        List<DecisionPayload.ProductRow> production = new ArrayList<>();
        for (Scenario.ProductSpec spec : scenario.products()) {
            production.add(new DecisionPayload.ProductRow(spec.code(), BigDecimal.valueOf(300)));
        }
        List<DecisionPayload.ChannelRow> marketing = new ArrayList<>();
        for (Scenario.ChannelSpec channel : scenario.channels()) {
            marketing.add(new DecisionPayload.ChannelRow(channel.code(),
                    spend.getOrDefault(channel.code(), BigDecimal.ZERO)));
        }
        DecisionPayload decision = new DecisionPayload(production, List.of(), marketing, null,
                List.of(), List.of(), BigDecimal.ZERO, false);
        return new ScoringEngine.TeamInput(id, "ACTIVE", new BigDecimal("2000000000"),
                BigDecimal.ZERO, BigDecimal.ZERO, staff, products, Map.of(), decision);
    }
}
