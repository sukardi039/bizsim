package com.bizsim.sim;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Draws a {@link MarketSharePie} as a **solid pie** (D121) with its coverage stated beside it.
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
    /**
     * D123: the pie's **base** — a disc under the slices, so the wedge a class failed to serve reads as
     * demand it did not supply rather than as a hole in the drawing. Same colour as the legend's unserved
     * swatch, so picture and legend agree.
     */
    private static final String BASE = "#2f3947";

    private static final int SIZE = 220;
    private static final int CX = SIZE / 2;
    private static final int CY = SIZE / 2;
    /** D121: no inner radius — a wedge from the centre, not a ring. */
    private static final double OUTER = 96;

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

        // 0. The base disc, first so every slice sits on it (D123).
        out.append("<circle cx=\"").append(CX).append("\" cy=\"").append(CY).append("\" r=\"")
                .append(n(OUTER)).append("\" fill=\"").append(BASE).append("\"/>");

        // 1. No separate "coverage" arc. The ring below is drawn on the served market, so the part the
        //    class did not supply is simply not drawn: the gap *is* the coverage. A second grey arc at a
        //    different radius read as the unserved slice and contradicted the legend (reported 20 Nov).
        double coverage = totalSold.doubleValue() / pie.servedMarket().doubleValue() * 100.0;
        coverage = Math.min(100.0, Math.max(0.0, coverage));

        // 2. The ring itself: each company's share of the **served market**, so demand the class could
        //    not supply is a real gap in the circle rather than a label contradicting a closed ring.
        //    Drawing it on `totalSold` (as this did) always sums to 100 % and hides the gap the legend
        //    reports — reported 20 Nov 2026: P1 showed a full ring beside "24.9 % unserved".
        double start = -90;
        int colour = 0;
        for (MarketSharePie.Slice slice : pie.slices()) {
            if (slice.sold().signum() <= 0) {
                colour++;
                continue;
            }
            double share = pie.servedMarket().signum() == 0 ? 0
                    : slice.sold().doubleValue() / pie.servedMarket().doubleValue() * 100.0;
            double sweep = share * 3.6;
            if (sweep > 0) {
                out.append(wedge(start, sweep, OUTER,
                        slice.autoPlayer() ? autoColour(colour) : PALETTE[colour % PALETTE.length],
                        sweep));
                start += sweep;
            }
            colour++;
        }
        out.append("</svg>");

        // One number per company. There were two — share of the served market and share of what was
        // actually sold — which are the same figure renormalised, identical whenever nothing is unserved
        // (reported 20 Nov 2026: "two numbers after the company name, what are they?"). The served-market
        // share is the one the ring is drawn from, so it is the one the legend states.
        out.append("<table class=\"share-legend\"><thead><tr><th></th><th></th>")
                .append("<th class=\"num\">").append(esc(coverageLabel)).append("</th>")
                .append("</tr></thead><tbody>");
        colour = 0;
        for (MarketSharePie.Slice slice : pie.slices()) {
            out.append("<tr><td><span class=\"swatch\" style=\"background:")
                    .append(slice.autoPlayer() ? autoColour(colour) : PALETTE[colour % PALETTE.length])
                    .append("\"></span></td><td>").append(esc(slice.label()));
            // The auto player is shown by **company name only** (owner's ruling, 21 Nov 2026). No
            // "(auto)" tag and no persona: the class reads a company that traded, which is what the
            // chart measures. The colour difference stays, so the facilitator can still see a seat was
            // machine-played without the projection putting a label on it.
            out.append("</td><td class=\"num\">").append(fmt(slice.sharePercent()))
                    .append("</td></tr>");
            colour++;
        }
        out.append("</tbody><tfoot><tr><td></td><td>").append(esc(unservedLabel))
                .append("</td><td class=\"num\">").append(fmt(pie.unservedPercent()))
                .append("</td></tr></tfoot></table>");
        out.append("</figure>");
        return out.toString();
    }

    /**
     * One slice: a wedge from the centre (D121), `from` in degrees clockwise from twelve o'clock.
     *
     * @param recorded the sweep to publish as `data-sweep`, or null. Recording it makes the drawing
     *                 measurable from outside: a test can prove that a class which under-serves leaves a
     *                 real gap, instead of a closed circle contradicting its own legend.
     */
    private static String wedge(double from, double sweep, double outer, String colour, Double recorded) {
        String marker = recorded == null ? "" : " data-sweep=\"" + n(recorded) + "\"";
        // A lone 100 % slice is the whole circle. Two arcs rather than one, because an SVG arc cannot
        // close on its own start point and still fill.
        if (sweep >= 359.999) {
            return "<circle" + marker + " cx=\"" + CX + "\" cy=\"" + CY + "\" r=\"" + n(outer)
                    + "\" fill=\"" + colour + "\"/>";
        }
        double to = from + sweep;
        double[] a = point(from, outer);
        double[] b = point(to, outer);
        int large = sweep > 180 ? 1 : 0;
        return "<path d=\"M" + CX + " " + CY
                + " L" + n(a[0]) + " " + n(a[1])
                + " A" + n(outer) + " " + n(outer) + " 0 " + large + " 1 " + n(b[0]) + " " + n(b[1])
                + " Z\" fill=\"" + colour + "\"" + marker + "/>";
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
