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
    }

    /** An auto player keeps its company name; the bot's own name never reaches the chart. */
    @Test
    void an_auto_player_is_shown_by_company_only() {
        // Owner's ruling: company name only. The slice must never carry the bot's persona, and the
        // legend must not mark it "(auto)" either — the class reads a company that traded.
        List<MarketSharePie.Company> companies = List.of(
                new MarketSharePie.Company(1, "Company 1", false,
                        List.of(line("P1", 400, 0, 400))),
                new MarketSharePie.Company(2, "Company 2", true,
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

    /** The donut is one closed shape per slice, and a lone 100 % slice is a ring, not nothing. */
    @Test
    void a_single_company_holding_the_whole_market_draws_a_full_ring() {
        List<MarketSharePie.Company> companies = List.of(
                new MarketSharePie.Company(1, "Company 1", false, List.of(line("P1", 900, 0, 900))));

        MarketSharePie.Pie pie = MarketSharePie.build(List.of("P1"), Map.of(),
                Map.of("P1", BigDecimal.valueOf(900)), companies).get(0);

        String html = MarketShareDonut.render("en", pie, "Basic", "unserved", "—", "share of market");
        assertThat(html).as("a 100 % slice must be a full ring; an arc from a point to itself draws nothing")
                .contains("<circle");
    }
}
