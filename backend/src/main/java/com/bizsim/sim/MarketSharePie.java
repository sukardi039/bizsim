package com.bizsim.sim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-product market share, as a pie the facilitator can show the class.
 *
 * <p><strong>What a slice measures.</strong> The statement's own share (D97) is `sold / pool` — the whole
 * market, including the part no company in the class can reach (`baselineShare`, 18 % of the pool). A pie
 * drawn on that denominator tops out around 82 % and every stockout silently vanishes from it. That
 * chart would add up to less than a circle and explain nothing.
 *
 * <p>So the pie is drawn on the <strong>served market</strong>: `pool × (1 − baselineShare)`, the pieces
 * the class actually contested. Every company's slice is `sold / served`, and whatever the class failed
 * to buy is shown as its own slice rather than dropped. The slices then add to the whole by construction,
 * and a stockout is visible as the gap it is — which is the single most useful thing a facilitator can
 * point at.
 *
 * <p><strong>Auto players.</strong> A bot seat is a `player` row with `bot_profile_id` set; it owns a team
 * with a company name like any other. The chart labels it by that <em>company name only</em> and never by
 * the bot's persona or profile name (owner's ruling, 21 Nov 2026): the class should see that a company
 * was played by the system, not which brain it was.
 */
public final class MarketSharePie {

    /** One slice: who, how much they sold, and what fraction of the served market that is. */
    public record Slice(String label, BigDecimal sold, BigDecimal sharePercent, boolean autoPlayer) {
    }

    /** One product's pie for one round. `unserved` is demand the class could not supply. */
    public record Pie(String product, String productName, BigDecimal servedMarket,
                      List<Slice> slices, BigDecimal unserved, BigDecimal unservedPercent) {
    }

    /** A company as the pie needs it: its label, its product lines, and whether it is a bot seat. */
    public record Company(long teamId, String name, boolean autoPlayer,
                          List<Map<String, Object>> lines) {
    }

    private MarketSharePie() {
    }

    /**
     * Builds one pie per product.
     *
     * @param servedMarketByProduct each product's served market in whole pieces — `pool × (1 − baseline)`,
     *                              the same number the allocator divided
     * @param companies             every company in the run, human and auto alike
     */
    public static List<Pie> build(List<String> products, Map<String, String> productNames,
                                  Map<String, BigDecimal> servedMarketByProduct,
                                  List<Company> companies) {
        List<Pie> pies = new ArrayList<>();
        for (String product : products) {
            BigDecimal served = servedMarketByProduct.getOrDefault(product, BigDecimal.ZERO);

            // One line per company, in team order, so the slices are stable between rounds.
            Map<Long, BigDecimal> soldByTeam = new LinkedHashMap<>();
            Map<Long, BigDecimal> lostByTeam = new LinkedHashMap<>();
            for (Company company : companies) {
                BigDecimal sold = BigDecimal.ZERO;
                BigDecimal lost = BigDecimal.ZERO;
                for (Map<String, Object> line : company.lines()) {
                    if (!product.equals(String.valueOf(line.get("product")))) {
                        continue;
                    }
                    sold = sold.add(decimal(line.get("sold")));
                    lost = lost.add(decimal(line.get("lost")));
                }
                soldByTeam.put(company.teamId(), sold);
                lostByTeam.put(company.teamId(), lost);
            }

            BigDecimal totalSold = soldByTeam.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            // The class could not supply everything it was entitled to: stockouts, capacity, insolvency.
            // Shown as its own slice, so the circle is complete and the gap is named.
            BigDecimal unserved = served.subtract(totalSold).max(BigDecimal.ZERO);

            List<Slice> slices = new ArrayList<>();
            for (Company company : companies) {
                BigDecimal sold = soldByTeam.get(company.teamId());
                if (sold.signum() == 0) {
                    // A company that sold nothing is not a zero-width slice nobody can read; it is
                    // absent, and the legend has to say so. Keep it only if it lost sales — that is
                    // information — otherwise drop it.
                    if (lostByTeam.get(company.teamId()).signum() == 0) {
                        continue;
                    }
                }
                slices.add(new Slice(company.name(), sold, percent(sold, served), company.autoPlayer()));
            }
            pies.add(new Pie(product, productNames.getOrDefault(product, product), served, slices,
                    unserved, percent(unserved, served)));
        }
        return pies;
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole.signum() <= 0) {
            return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal big) {
            return big;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
