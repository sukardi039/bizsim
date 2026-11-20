package com.bizsim.sim;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Draws a {@link MarketSharePie} as a donut with two readings on it.
 *
 * <p><strong>Why two readings.</strong> The first attempt drew one ring divided by the served market and
 * gave the unserved remainder its own slice. Arithmetically correct, and useless: a class that stocks out
 * badly sold 13 % of the market, so 87 % of the circle was one grey wedge and the contest between
 * companies was a hairline. A facilitator looking at it learns "we did badly", not "who won".
 *
 * <p>So the donut carries both questions, which is what a debrief actually asks:
 *
 * <ul>
 *   <li>the <strong>ring</strong> is the contest: each company's share of what the class <em>sold</em>.
 *       It always fills the circle, so a 73 %-of-sales company is visibly three-quarters of the ring.</li>
 *   <li>the <strong>arc outside it</strong> is coverage: how much of the served market the class actually
 *       supplied. Dim where nothing was sold, bright where it was, so a bad round is legible at a glance
 *       without swamping the comparison.</li>
 * </ul>
 *
 * <p>Both numbers are in the legend, so nothing rests on reading an area by eye.
 */
public final class MarketShareDonut {

    private static final String[] PALETTE = {
            "#3b82f6", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6", "#06b6d4",
            "#ec4899", "#84cc16", "#f97316", "#6366f1", "#14b8a6", "#a855f7"
    };
    private static final String MUTED = "#6b7280";

    private static final int SIZE = 220;
    private static final int CX = SIZE / 2;
    private static final int CY = SIZE / 2;
    private static final double OUTER = 92;
    private static final double INNER = 58;
    /** The coverage arc sits outside the ring, with a hairline of background between them. */
    private static final double COVER_OUTER = 104;
    private static final double COVER_INNER = 97;

    private MarketShareDonut() {
    }

    public static String render(String lang, MarketSharePie.Pie pie, String title,
                                String unservedLabel, String emptyLabel, String coverageLabel) {
        StringBuilder out = new StringBuilder(3072);
        out.append("<figure class=\"share-pie\">");
        out.append("<figcaption>").append(esc(title)).append("</figcaption>");
        if (pie.servedMarket().signum() <= 0) {
            out.append("<p class=\"muted\">").append(esc(emptyLabel)).append("</p></figure>");
            return out.toString();
        }

        BigDecimal totalSold = pie.slices().stream().map(MarketSharePie.Slice::sold)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        out.append("<svg viewBox=\"0 0 ").append(SIZE).append(' ').append(SIZE).append("\" role=\"img\"")
                .append(" aria-label=\"").append(esc(title)).append("\">");

        // 1. Coverage arc: the part of the served market the class actually supplied, in muted fill.
        double coverage = totalSold.doubleValue() / pie.servedMarket().doubleValue() * 100.0;
        coverage = Math.min(100.0, Math.max(0.0, coverage));
        if (coverage > 0) {
            out.append(ring(-90, coverage * 3.6, COVER_OUTER, COVER_INNER, "#2f3947"));
        }

        // 2. The ring itself: each company's share of what was sold, so it always fills the circle.
        double start = -90;
        int colour = 0;
        for (MarketSharePie.Slice slice : pie.slices()) {
            if (slice.sold().signum() <= 0) {
                colour++;
                continue;
            }
            double share = totalSold.signum() == 0 ? 0
                    : slice.sold().doubleValue() / totalSold.doubleValue() * 100.0;
            double sweep = share * 3.6;
            if (sweep > 0) {
                out.append(ring(start, sweep, OUTER, INNER,
                        slice.autoPlayer() ? autoColour(colour) : PALETTE[colour % PALETTE.length]));
                start += sweep;
            }
            colour++;
        }
        out.append("</svg>");

        out.append("<table class=\"share-legend\"><thead><tr><th></th><th></th>")
                .append("<th class=\"num\">").append(esc(coverageLabel)).append("</th>")
                .append("<th class=\"num\">of sales</th></tr></thead><tbody>");
        colour = 0;
        for (MarketSharePie.Slice slice : pie.slices()) {
            double ofSales = totalSold.signum() == 0 ? 0
                    : slice.sold().doubleValue() / totalSold.doubleValue() * 100.0;
            out.append("<tr><td><span class=\"swatch\" style=\"background:")
                    .append(slice.autoPlayer() ? autoColour(colour) : PALETTE[colour % PALETTE.length])
                    .append("\"></span></td><td>").append(esc(slice.label()));
            // The auto player is shown by **company name only** (owner's ruling, 21 Nov 2026). No
            // "(auto)" tag and no persona: the class reads a company that traded, which is what the
            // chart measures. The colour difference stays, so the facilitator can still see a seat was
            // machine-played without the projection putting a label on it.
            out.append("</td><td class=\"num\">").append(fmt(slice.sharePercent()))
                    .append("</td><td class=\"num\">").append(fmt(BigDecimal.valueOf(ofSales)))
                    .append("</td></tr>");
            colour++;
        }
        out.append("</tbody><tfoot><tr><td></td><td>").append(esc(unservedLabel))
                .append("</td><td class=\"num\">").append(fmt(pie.unservedPercent()))
                .append("</td><td class=\"num muted\">—</td></tr>")
                .append("<tr><td></td><td class=\"muted\">").append(esc(coverageLabel))
                .append("</td><td class=\"num\">").append(fmt(BigDecimal.valueOf(coverage)))
                .append("</td><td></td></tr></tfoot></table>");
        out.append("</figure>");
        return out.toString();
    }

    /** A ring segment between two radii, `from` in degrees clockwise from twelve o'clock. */
    private static String ring(double from, double sweep, double outer, double inner, String colour) {
        if (sweep >= 359.999) {
            return "<circle cx=\"" + CX + "\" cy=\"" + CY + "\" r=\"" + ((outer + inner) / 2)
                    + "\" fill=\"none\" stroke=\"" + colour + "\" stroke-width=\"" + (outer - inner) + "\"/>";
        }
        double to = from + sweep;
        double[] a1 = point(from, outer);
        double[] a2 = point(to, outer);
        double[] b2 = point(to, inner);
        double[] b1 = point(from, inner);
        int large = sweep > 180 ? 1 : 0;
        return "<path d=\"M" + n(a1[0]) + " " + n(a1[1])
                + " A" + n(outer) + " " + n(outer) + " 0 " + large + " 1 " + n(a2[0]) + " " + n(a2[1])
                + " L" + n(b2[0]) + " " + n(b2[1])
                + " A" + n(inner) + " " + n(inner) + " 0 " + large + " 0 " + n(b1[0]) + " " + n(b1[1])
                + " Z\" fill=\"" + colour + "\"/>";
    }

    private static double[] point(double degrees, double radius) {
        double radians = Math.toRadians(degrees);
        return new double[]{CX + radius * Math.cos(radians), CY + radius * Math.sin(radians)};
    }

    /** Auto players get a grey pair so they read as "not a human team" at a glance. */
    private static String autoColour(int index) {
        return index % 2 == 0 ? MUTED : "#9ca3af";
    }

    private static String n(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String fmt(BigDecimal value) {
        return value.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public static String renderAll(String lang, List<MarketSharePie.Pie> pies, String titlePrefix,
                                   String unservedLabel, String emptyLabel, String coverageLabel) {
        StringBuilder out = new StringBuilder();
        out.append("<div class=\"share-pies\">");
        for (MarketSharePie.Pie pie : pies) {
            String title = titlePrefix + (pie.productName() == null || pie.productName().isBlank()
                    ? pie.product() : pie.productName());
            out.append(render(lang, pie, title, unservedLabel, emptyLabel, coverageLabel));
        }
        out.append("</div>");
        return out.toString();
    }
}
