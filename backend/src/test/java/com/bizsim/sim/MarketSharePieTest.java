package com.bizsim.sim;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D112: the per-product market-share pie.
 *
 * <p>Two properties matter more than the drawing, and both are asserted here because both have been got
 * wrong in this codebase before in other guises:
 *
 * <ul>
 *   <li><strong>The slices add up.</strong> The statement's own share divides by the whole pool, which
 *       includes an 18 % nobody in the class can reach — a pie on that denominator stops short of a
 *       circle. The pie here divides by the <em>served</em> market and gives the unserved remainder its own
 *       slice, so the total is 100 % and a stockout is visible rather than silent.</li>
 *   <li><strong>An auto player is a company, not a brain.</strong> The class sees "Company 7 (auto)" and
 *       never the bot profile's name.</li>
 * </ul>
 */
class MarketSharePieTest {

    private static Map<String, Object> line(String product, double sold, double lost, double demand) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("product", product);
        row.put("sold", BigDecimal.valueOf(sold));
        row.put("lost", BigDecimal.valueOf(lost));
        row.put("demand", BigDecimal.valueOf(demand));
        return row;
    }

    /** Three companies splitting a served market of 820, with 180 left unsold by the class. */
    @Test
    void the_slices_and_the_unserved_remainder_add_up_to_one_hundred_percent() {
        List<MarketSharePie.Company> companies = List.of(
                new MarketSharePie.Company(1, "Company 1", false,
                        List.of(line("P1", 300, 0, 300))),
                new MarketSharePie.Company(2, "Company 2", false,
                        List.of(line("P1", 200, 50, 250))),
                new MarketSharePie.Company(3, "Company 3", false,
                        List.of(line("P1", 200, 70, 270))));

        List<MarketSharePie.Pie> pies = MarketSharePie.build(List.of("P1"), Map.of("P1", "Basic"),
                Map.of("P1", BigDecimal.valueOf(1000)), companies);

        MarketSharePie.Pie pie = pies.get(0);
        // The served market is handed in — in production it is the sum of the companies' demand, which is
        // what the allocator divided. Here it is 1,000 and the class demanded 300 + 250 + 270 = 820.
        assertThat(pie.servedMarket()).isEqualByComparingTo("1000");

        BigDecimal total = pie.slices().stream().map(MarketSharePie.Slice::sharePercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add).add(pie.unservedPercent());
        assertThat(total).as("slices + unserved must be a whole circle").isEqualByComparingTo("100.0");
        // The companies sold 300 + 200 + 200 = 700 of the 1,000 pieces; the 300 nobody bought is the
        // named remainder. (Their `demand` totals 820 — 120 of it was lost to stockouts, which is a
        // different fact from what the class failed to buy, and the pie shows what was *sold*.)
        assertThat(pie.unservedPercent()).isEqualByComparingTo("30.0");
    }

    /** A class that could not supply its market shows the gap as a named slice. */
    @Test
    void demand_the_class_could_not_serve_is_a_slice_not_a_silent_hole() {
        List<MarketSharePie.Company> companies = List.of(
                new MarketSharePie.Company(1, "Company 1", false,
                        // Entitled to 500, sold 300, lost 200 to a stockout.
                        List.of(line("P1", 300, 200, 500))));

        List<MarketSharePie.Pie> pies = MarketSharePie.build(List.of("P1"), Map.of(),
                Map.of("P1", BigDecimal.valueOf(500)), companies);

        MarketSharePie.Pie pie = pies.get(0);
        assertThat(pie.slices()).hasSize(1);
        assertThat(pie.slices().get(0).sharePercent()).isEqualByComparingTo("60.0");
        assertThat(pie.unservedPercent()).as("the 200 nobody could buy").isEqualByComparingTo("40.0");

        // D112: the *drawing* must show that gap, not just the legend. The ring is swept on the served
        // market, so the wedges cover 60 % of the circle and 40 % stays empty. Drawing it on units sold
        // closed the ring while the legend reported 40 % unserved — the chart contradicted itself.
        String html = MarketShareDonut.render("en", pie, "Basic", "Demand the class could not serve",
                "—", "share of market");
        assertThat(sweptDegrees(html)).as("60 %% of the ring, not a closed circle")
                .isCloseTo(216.0, org.assertj.core.data.Offset.offset(1.0));
    }

    /** Total degrees the company wedges sweep, summed from the sweeps the renderer records. */
    private static double sweptDegrees(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("data-sweep=\"([0-9.]+)\"")
                .matcher(html);
        double total = 0;
        while (m.find()) {
            total += Double.parseDouble(m.group(1));
        }
        return total;
    }

    /** An auto player keeps its company name; the bot's own name never reaches the chart. */
    @Test
    void an_auto_player_is_shown_by_company_only() {
        // Owner's ruling: company name only. The slice must never carry the bot's persona, and the
        // legend must not mark it "(auto)" either — the class reads a company that traded.
        List<MarketSharePie.Company> companies = List.of(
                new MarketSharePie.Company(1, "Company 1", false, null,
                        List.of(line("P1", 400, 0, 400))),
                new MarketSharePie.Company(2, "Company 2", true, "COST",
                        List.of(line("P1", 600, 0, 600))));

        MarketSharePie.Pie pie = MarketSharePie.build(List.of("P1"), Map.of(),
                Map.of("P1", BigDecimal.valueOf(1000)), companies).get(0);

        assertThat(pie.slices()).extracting(MarketSharePie.Slice::label)
                .containsExactly("Company 1", "Company 2");
        assertThat(pie.slices().get(1).autoPlayer()).isTrue();
        assertThat(pie.slices().get(0).autoPlayer()).isFalse();
        // The rendering shows the company and **nothing else about the seat**: no "(auto)" tag, no
        // persona. Colour still differs, but no label tells the class a seat was machine-played.
        String html = MarketShareDonut.render("en", pie, "Basic", "Demand the class could not serve", "—",
                "share of market");
        assertThat(html).contains("Company 2");
        assertThat(html).as("no auto tag on the legend").doesNotContain("(auto)", "自动玩家");
        assertThat(html).as("no persona leaked").doesNotContain("bot", "BALANCED", "AGGRESSIVE", "CAUTIOUS");
    }

    /** A company that sold nothing and lost nothing is absent, not a zero-width wedge. */
    @Test
    void a_company_that_never_traded_is_left_out() {
        List<MarketSharePie.Company> companies = List.of(
                new MarketSharePie.Company(1, "Company 1", false, List.of(line("P1", 500, 0, 500))),
                new MarketSharePie.Company(2, "Company 2", false, List.of(line("P1", 0, 0, 0))));

        MarketSharePie.Pie pie = MarketSharePie.build(List.of("P1"), Map.of(),
                Map.of("P1", BigDecimal.valueOf(500)), companies).get(0);

        assertThat(pie.slices()).extracting(MarketSharePie.Slice::label).containsExactly("Company 1");
    }

    /**
     * D121: a solid pie. Each slice is a wedge from the centre — measured from the path itself, because a
     * ring would draw the same legend and the same percentages and only a shape check tells them apart.
     */
    @Test
    void every_slice_is_a_wedge_from_the_centre_not_a_ring() {
        List<MarketSharePie.Company> companies = List.of(
                new MarketSharePie.Company(1, "Company 1", false, List.of(line("P1", 600, 0, 600))),
                new MarketSharePie.Company(2, "Company 2", false, List.of(line("P1", 300, 0, 300))));

        MarketSharePie.Pie pie = MarketSharePie.build(List.of("P1"), Map.of(),
                Map.of("P1", BigDecimal.valueOf(900)), companies).get(0);

        String html = MarketShareDonut.render("en", pie, "Basic", "unserved", "—", "share of market");
        // 220 / 2 = 110: the centre. Every path starts there and closes back to it.
        assertThat(html).as("each slice starts at the centre").contains("d=\"M110 110 L");
        assertThat(html).as("no ring, so no stroke-only circle for a partial slice").doesNotContain("stroke-width");
    }

    /** A lone 100 % slice is the whole circle, not nothing. */
    @Test
    void a_single_company_holding_the_whole_market_draws_a_full_circle() {
        List<MarketSharePie.Company> companies = List.of(
                new MarketSharePie.Company(1, "Company 1", false, List.of(line("P1", 900, 0, 900))));

        MarketSharePie.Pie pie = MarketSharePie.build(List.of("P1"), Map.of(),
                Map.of("P1", BigDecimal.valueOf(900)), companies).get(0);

        String html = MarketShareDonut.render("en", pie, "Basic", "unserved", "—", "share of market");
        assertThat(html).as("a 100 % slice must be a full circle; an arc from a point to itself draws nothing")
                .contains("<circle");
    }

    /**
     * D123: the pie sits on a base disc. Slices sweep on the served market, so an under-served class
     * leaves a wedge nobody drew; without the base that wedge is page background, which reads as a hole
     * in the chart rather than as the demand the class failed to supply.
     */
    @Test
    void the_pie_has_a_base_disc_under_its_slices() {
        List<MarketSharePie.Company> companies = List.of(
                new MarketSharePie.Company(1, "Company 1", false, List.of(line("P1", 100, 0, 100))));

        MarketSharePie.Pie pie = MarketSharePie.build(List.of("P1"), Map.of(),
                Map.of("P1", BigDecimal.valueOf(400)), companies).get(0);

        String html = MarketShareDonut.render("en", pie, "Basic", "unserved", "—", "share of market");
        assertThat(html).as("a filled disc under the wedges, in the gap colour")
                .contains("<circle cx=\"110\" cy=\"110\" r=\"96.00\" fill=\"#2f3947\"/>");
        // The base comes first: an SVG paints in document order, so a disc drawn after the slices would
        // cover them.
        assertThat(html.indexOf("<circle cx=\"110\"")).as("base is drawn before the first wedge")
                .isLessThan(html.indexOf("d=\"M110 110 L"));
    }

    /**
     * D139: a bot is coloured by the Porter position it plays, so a debrief can read which slice is the cost
     * leader. The colour must come from the **position**, not from the slice's index — two bots at different
     * positions must differ even when they sit next to each other, and the same position must look the same in
     * two different pies. The four hues are deliberately outside the trainee palette, so a machine seat is still
     * visibly a machine seat.
     */
    @Test
    void an_auto_player_is_coloured_by_its_position() {
        List<MarketSharePie.Company> companies = List.of(
                //                          team  name          auto  position
                new MarketSharePie.Company(1, "Alpha Ltd", true, "COST", List.of(line("P1", 250, 0, 250))),
                new MarketSharePie.Company(2, "Bravo Ltd", true, "DIFFERENTIATION_FOCUS",
                        List.of(line("P1", 250, 0, 250))),
                new MarketSharePie.Company(3, "Charlie Ltd", false, null, List.of(line("P1", 250, 0, 250))),
                new MarketSharePie.Company(4, "Delta Ltd", true, "COST", List.of(line("P1", 250, 0, 250))));

        MarketSharePie.Pie pie = MarketSharePie.build(List.of("P1"), Map.of(),
                Map.of("P1", BigDecimal.valueOf(1000)), companies).get(0);

        assertThat(pie.slices()).extracting(MarketSharePie.Slice::position)
                .containsExactly("COST", "DIFFERENTIATION_FOCUS", null, "COST");

        String html = MarketShareDonut.render("en", pie, "Basic", "Demand the class could not serve", "—",
                "share of market");
        // The two COST bots share a hue; the differentiation-focus bot has another.
        assertThat(html).as("cost leadership has its own hue").contains("#2f7d99");
        assertThat(html).as("differentiation focus has its own hue").contains("#7b4fa8");
        // D141: two COST bots now take the two shades of the cost hue — so the dark shade appears for one slice
        // and its swatch (2), and the light shade for the other (2).
        int darkCost = html.split(java.util.regex.Pattern.quote("#2f7d99"), -1).length - 1;
        int lightCost = html.split(java.util.regex.Pattern.quote("#7fb4c9"), -1).length - 1;
        assertThat(darkCost).as("one cost bot in the dark shade").isEqualTo(2);
        assertThat(lightCost).as("the other cost bot in the light shade").isEqualTo(2);
        assertThat(html).as("the differentiation focus bot is a different colour")
                .contains("#7b4fa8");
        assertThat(html).as("no bot took a trainee palette hue")
                .doesNotContain("#f2994a", "#27ae60", "#eb5757");
        // A human seat keeps the ordinary palette (its own index within the slice order), and no position
        // colour leaks onto it — the drawn hue must be one the bot family does not use.
        assertThat(html).as("the trainee slice uses the ordinary palette, not a bot hue")
                .contains("#f59e0b");
    }

    /** A bot whose position is unknown (profile removed since the run) keeps the neutral grey, not a made-up hue. */
    @Test
    void an_auto_player_with_no_known_position_stays_neutral() {
        List<MarketSharePie.Company> companies = List.of(
                new MarketSharePie.Company(1, "Ghost Ltd", true, null, List.of(line("P1", 500, 0, 500))));
        MarketSharePie.Pie pie = MarketSharePie.build(List.of("P1"), Map.of(),
                Map.of("P1", BigDecimal.valueOf(1000)), companies).get(0);
        String html = MarketShareDonut.render("en", pie, "Basic", "Demand the class could not serve", "—",
                "share of market");
        assertThat(html).as("falls back to the muted grey").contains("#6b7280");
    }

    /**
     * D141: the defect the owner saw on screen — two bots playing the **same position** were drawn in exactly the
     * same colour, and the four position hues were so close that cost and cost-focus read alike.
     *
     * <p>Now: four properly separated hues, and a second company at the same position takes a lighter shade of the
     * same hue. The shade is assigned from the team id, so a company keeps its colour in every product and round
     * instead of changing with slice order.
     */
    @Test
    void two_bots_at_the_same_position_are_drawn_in_different_shades() {
        List<MarketSharePie.Company> companies = List.of(
                new MarketSharePie.Company(1, "Kenny Ltd", true, "DIFFERENTIATION_FOCUS",
                        List.of(line("P1", 300, 0, 300))),
                new MarketSharePie.Company(2, "Johny Ltd", true, "DIFFERENTIATION_FOCUS",
                        List.of(line("P1", 300, 0, 300))),
                new MarketSharePie.Company(3, "Charlie Ltd", true, "COST",
                        List.of(line("P1", 300, 0, 300))),
                new MarketSharePie.Company(4, "Benny Ltd", false, null,
                        List.of(line("P1", 100, 0, 100))));

        MarketSharePie.Pie pie = MarketSharePie.build(List.of("P1"), Map.of(),
                Map.of("P1", BigDecimal.valueOf(1000)), companies).get(0);
        String html = MarketShareDonut.render("en", pie, "Basic", "Demand the class could not serve", "—",
                "share of market");

        // The two differentiation-focus bots take the two shades of the violet hue, not the same one.
        assertThat(html).as("first differentiation-focus bot").contains("#7b4fa8");
        assertThat(html).as("second differentiation-focus bot takes the lighter shade").contains("#b597d1");
        // Cost has its own, clearly different hue.
        assertThat(html).as("cost leadership is a separate hue").contains("#2f7d99");
        // No two bots share a colour.
        assertThat(html.split(java.util.regex.Pattern.quote("#7b4fa8"), -1).length - 1)
                .as("the dark violet appears for one slice and its swatch only").isEqualTo(2);
    }
}
