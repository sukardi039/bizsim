package com.bizsim.sim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * D102: builds the shared position map from the console's own drawing.
 *
 * <p>The figure is not drawn here. {@code web/scripts/map-template.mjs} renders the console's
 * {@code PositionMap} component at build time for a fixed six-slot dataset and stores the resulting SVG
 * as a template, with {@code {{name0}}}… for the companies and {@code __NUM__} for every figure. This
 * class substitutes the round's real numbers into that geometry — legitimate because both axes are
 * indices around 1.00, so the <em>positions</em> move with the data while the <em>words</em> do not.
 *
 * <p>A second drawing in Java was the alternative and was rejected: two drawings of one chart drift, and
 * the drift is silent. The price of that decision is that this class must check its own substitution
 * against the template it was given, which it does, refusing to serve a page it cannot account for.
 */
@Component
public class PositionMapPage {

    /**
     * D118: above this many companies the map labels each bubble with its number and prints the names in a
     * legend instead. Measured, not guessed: at thirty companies the name labels overlap into a single
     * unreadable line across the plot, which is a chart that lies about being readable.
     */
    public static final int NAME_LIMIT = 12;

    /** One company as the picture needs it: where it sits, and how big its bubble is. */
    public record Company(String name, double priceIndex, double qualityIndex, double brand, double share) {
    }

    /** One round's inputs: the scoreboard rows, and each company's product lines from its statement. */
    public record RoundInput(List<Map<String, Object>> scoreboard,
                             Map<Long, List<Map<String, Object>>> linesByTeam) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private volatile JsonNode template;
    /**
     * Where the console is served from, when one is beside the jar (D118). Spring's own property, not a
     * system property: `--bizsim.web.dir=` lands in the environment, not in {@code System.getProperties()}.
     */
    private final String webDir;

    public PositionMapPage(
            @org.springframework.beans.factory.annotation.Value("${bizsim.web.dir:}") String webDir) {
        this.webDir = webDir;
    }

    /**
     * The build product, read once. Missing means a broken build, and the caller answers 503 rather than
     * drawing an empty chart that would look like real data.
     */
    public JsonNode template() {
        JsonNode loaded = template;
        if (loaded == null) {
            synchronized (this) {
                loaded = template;
                if (loaded == null) {
                    try (java.io.InputStream in = open()) {
                        loaded = json.readTree(in);
                        template = loaded;
                    } catch (Exception e) {
                        throw new TemplateMissing("map-template.json is missing: run `npm run map-template` "
                                + "in web/ and rebuild the image", e);
                    }
                }
            }
        }
        return loaded;
    }

    /** Thrown when the console never built the template — a deployment fault, not a user error. */
    public static class TemplateMissing extends RuntimeException {
        public TemplateMissing(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private java.io.InputStream open() throws java.io.IOException {
        // D118: the console's own copy first when the console is beside us. A locally run jar otherwise
        // reads whatever template was baked into target/classes on some earlier build — and a stale
        // six-slot template against a thirty-company class is exactly the silent partial chart this
        // release removed. The image stage still supplies it for a deployed jar.
        if (webDir != null && !webDir.isBlank()) {
            java.io.File besideConsole = new java.io.File(webDir, "map-template.json");
            if (besideConsole.isFile()) {
                return new java.io.FileInputStream(besideConsole);
            }
        }
        for (String path : new String[] {"map-template.json", "static/map-template.json"}) {
            java.io.File file = new java.io.File(path);
            if (file.isFile()) {
                return new java.io.FileInputStream(file);
            }
            java.io.InputStream resource = getClass().getClassLoader().getResourceAsStream(path);
            if (resource != null) {
                return resource;
            }
        }
        throw new java.io.FileNotFoundException("map-template.json");
    }

    /** How many companies the picture has room for — six, the largest class the console seats. */
    public int slots() {
        return template().path("slots").size();
    }

    /**
     * Which axis the picture is drawn on. The template carries both variants; the round decides which is
     * honest — quality when the class differs on it, brand when nobody has bought any (D101's rule).
     */
    public String axisFor(List<RoundInput> history) {
        if (history.isEmpty()) {
            return "quality";
        }
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (Company company : positionsOf(history.get(history.size() - 1))) {
            low = Math.min(low, company.qualityIndex());
            high = Math.max(high, company.qualityIndex());
        }
        return high - low < 0.02 ? "brand" : "quality";
    }

    /** Positions for one round: each company's indices, weighted exactly as the console's map weights them. */
    public List<Company> positionsOf(RoundInput round) {
        Map<String, double[]> averages = averages(round.linesByTeam());
        List<Company> result = new ArrayList<>();
        for (Map<String, Object> row : round.scoreboard()) {
            long teamId = ((Number) row.get("team_id")).longValue();
            double[] indices = indices(round.linesByTeam().getOrDefault(teamId, List.of()), averages);
            result.add(new Company(String.valueOf(row.get("company_name")),
                    indices[0], indices[1], number(row.get("brand")), number(row.get("market_share"))));
        }
        return result;
    }

    /**
     * The finished {@code &lt;figure&gt;}: the caption, the chart, and the round's figures.
     *
     * <p>{@code companies} are in the scoreboard's order, which is the order the template's slots were laid
     * out in. A company beyond the class size has its bubble removed rather than left empty.
     */
    public String figure(String lang, List<Company> companies, boolean brandAxis) {
        JsonNode template = template();
        JsonNode variant = template.path("variants").path(brandAxis ? "brand" : "quality");
        String svg = variant.path("zh".equals(lang) ? "svgZh" : "svg").asText();

        int slots = template.path("slots").size();
        if (companies.size() > slots) {
            throw new IllegalArgumentException("the map draws up to " + slots + " companies, got " + companies.size());
        }
        // The template carries every slot, so unused ones must be paid for with a share that is about to
        // be dropped with their group — otherwise the substitution would shift every figure by one.
        List<Company> padded = new ArrayList<>(companies);
        while (padded.size() < slots) {
            padded.add(new Company("", 1, 1, 0, 0));
        }

        // One figure per bubble, in document order: its share, printed as a per-cent label. The
        // self-check below fails loudly if the component ever prints a different number of them, which is
        // the point — a share in the wrong bubble is worse than no chart at all.
        List<String> figures = new ArrayList<>();
        for (Company company : padded) {
            figures.add(percentNumber(company.share()));
        }
        svg = substitute(svg, template.path("tokens").path("number").asText(), figures);

        // D117/D118: a number on the bubble and a legend beside it, or the name itself when the class is
        // small enough for the names to fit.
        boolean compact = companies.size() > NAME_LIMIT;
        for (int i = 0; i < companies.size(); i++) {
            String label = compact ? String.valueOf(i + 1) : companies.get(i).name();
            svg = svg.replace(template.path("slots").get(i).path("name").asText(), escape(label));
        }
        for (int i = companies.size(); i < slots; i++) {
            svg = dropGroup(svg, template.path("slots").get(i).path("name").asText());
        }

        int namesLeft = countOf(svg, "{{name");
        int numbersLeft = countOf(svg, template.path("tokens").path("number").asText());
        if (namesLeft != 0 || numbersLeft != 0) {
            throw new IllegalStateException("map template did not substitute cleanly: "
                    + namesLeft + " names and " + numbersLeft + " figures left over");
        }
        if (!compact) {
            return svg;
        }
        // The legend is what makes the numbers usable: same order as the bubbles were numbered in.
        StringBuilder legend = new StringBuilder("<ol class=share-legend>");
        for (Company company : companies) {
            legend.append("<li><span>").append(escape(company.name())).append("</span><b>")
                    .append(percentNumber(company.share())).append("%</b></li>");
        }
        legend.append("</ol>");
        return svg + legend;
    }

    /**
     * One round's page: the picture, the numbers it is drawn from, and the caption — in the class
     * language, because a shared page has no account behind it and must carry everything a reader needs.
     */
    public String page(String title, String subtitle, String lang, List<Company> companies,
                       boolean brandAxis, String footer) {
        return singleRoundPage(title, subtitle, lang, companies, brandAxis, footer);
    }

    /** "Round 3" — or "第 3 期" — from the console's own catalogue, so a shared page matches the console. */
    public String roundCaption(String lang, PositionMapPage.RoundInput round) {
        int number = round.scoreboard().isEmpty() ? 0
                : ((Number) round.scoreboard().get(0).get("round")).intValue();
        String key = "debrief.round";
        return text(template(), lang, key) + " " + number;
    }

    /** The whole run: one section per computed round, newest first, each with its own picture. */
    public String runPage(String title, String subtitle, String lang, List<String> sections, String footer) {
        return sectionsPage(title, subtitle, lang, footer, sections);
    }

    /** One round: the figure, the same four columns as the debrief table, and the caption. */
    private String singleRoundPage(String title, String subtitle, String lang, List<Company> companies,
                                   boolean brandAxis, String footer) {
        JsonNode template = template();
        String figure = figure(lang, companies, brandAxis);
        String axisNote = (brandAxis
                ? text(template, lang, "debrief.positionFlatQuality") + " "
                : "") + text(template, lang, "debrief.positionMapQuadrants");
        StringBuilder rows = new StringBuilder();
        for (Company company : companies) {
            rows.append("<tr><td>").append(escape(company.name()))
                    .append("</td><td class=n>").append(String.format(java.util.Locale.ROOT, "%.2f", company.priceIndex()))
                    .append("</td><td class=n>")
                    .append(String.format(java.util.Locale.ROOT, "%.2f", brandAxis ? safeBrand(companies, company) : company.qualityIndex()))
                    .append("</td><td class=n>").append(percent(company.share()))
                    .append("</td></tr>");
        }
        return """
                <!doctype html>
                <html lang="%s"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="robots" content="noindex">
                <title>%s</title>
                <style>
                  :root{color-scheme:dark}
                  body{margin:0;background:#12151a;color:#e6eaf0;font-family:ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif}
                  main{max-width:780px;margin:0 auto;padding:1.6rem 1rem 2.6rem}
                  h1{font-size:1.2rem;margin:0 0 .2rem}
                  .sub{color:#8b96a6;font-size:.92rem;margin:0 0 1.1rem}
                  .note{color:#8b96a6;font-size:.86rem;margin:.7rem 0 0}
                  table{width:100%%;border-collapse:collapse;margin-top:1.2rem;font-size:.9rem}
                  th,td{padding:.32rem .45rem;border-bottom:1px solid #2a313d;text-align:left}
                  th{color:#8b96a6;font-weight:600;font-size:.78rem;text-transform:uppercase;letter-spacing:.04em}
                  td.n,th.n{text-align:right;font-variant-numeric:tabular-nums}
                  footer{color:#6b7484;font-size:.78rem;margin-top:1.8rem;border-top:1px solid #2a313d;padding-top:.8rem}
                  .position-frame{stroke:#3a4252;stroke-width:1;fill:#161b23}
                  .position-axis{stroke:#3a4252;stroke-width:1;stroke-dasharray:3 3}
                  .position-average{stroke:#8b94a6;stroke-width:1;stroke-dasharray:5 4;opacity:.55}
                  .position-tick{fill:#9aa3b5;font-size:8px}
                  .position-quadrant{fill:#9aa3b5;font-size:8.5px;opacity:.8;letter-spacing:.04em;text-transform:uppercase}
                  .position-trail{fill:none;stroke:#8b94a6;stroke-width:1;stroke-dasharray:2 3;opacity:.5}
                  .position-trail-start{fill:#8b94a6;opacity:.6}
                  .position-dot{fill:rgba(120,180,255,.5);stroke:rgba(150,200,255,.9)}
                  .position-dot.own{fill:rgba(120,220,160,.55);stroke:rgba(140,240,180,.95)}
                  .position-name{fill:#c8cedb;font-size:9px}
                  .position-share{fill:#eef2f8;font-size:10px;font-weight:600}
                  /* D118: above twelve companies the bubbles are numbered and the names live here.
                     A grid, not columns+float: the floated share used to collide with the next item's
                     number and the legend read as one run-on line. */
                  .share-legend{display:grid;grid-template-columns:1fr 1fr;gap:.05rem 1.4rem;margin:.9rem 0 0;padding-left:1.5rem;font-size:.82rem;color:#c8cedb}
                  .share-legend li{margin:0}
                  .share-legend li b{margin-left:.4rem;color:#eef2f8}
                  .position-label{fill:#9aa3b5;font-size:9px}
                  .position-map{margin:0}
                  .position-map figcaption h3{margin:0 0 .15rem;font-size:1rem}
                  .position-map figcaption p{margin:0 0 .4rem;font-size:.9rem;color:#8b96a6}
                  .position-map svg{width:min(620px,92vw);height:auto;display:block;margin:0 auto}
                </style></head><body><main>
                  <h1>%s</h1><p class="sub">%s</p>
                  %s
                  %s
                  <table><thead><tr><th>%s</th><th class=n>%s</th><th class=n>%s</th><th class=n>%s</th></tr></thead>
                    <tbody>%s</tbody></table>
                  <p class="note">%s</p>
                  <footer>%s</footer>
                </main></body></html>
                """.formatted(lang, escape(title), escape(title), escape(subtitle), figure,
                axisNote.isBlank() ? "" : "<p class=note>" + escape(axisNote) + "</p>",
                escape(text(template, lang, "debrief.company")),
                escape(text(template, lang, "debrief.positionPrice")),
                escape(text(template, lang, brandAxis ? "debrief.positionBrand" : "debrief.positionQuality")),
                escape(text(template, lang, "debrief.share")),
                rows,
                escape(text(template, lang, "debrief.positionMapHint")),
                escape(footer));
    }

    /** The whole run: one section per computed round, newest first, each with its own caption. */
    private String sectionsPage(String title, String subtitle, String lang, String footer,
                                List<String> sections) {
        JsonNode template = template();
        return """
                <!doctype html>
                <html lang="%s"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="robots" content="noindex">
                <title>%s</title>
                <style>
                  :root{color-scheme:dark}
                  body{margin:0;background:#12151a;color:#e6eaf0;font-family:ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif}
                  main{max-width:820px;margin:0 auto;padding:1.6rem 1rem 2.6rem}
                  h1{font-size:1.2rem;margin:0 0 .2rem}
                  .sub{color:#8b96a6;font-size:.92rem;margin:0 0 1.4rem}
                  section{border-top:1px solid #2a313d;padding:1.2rem 0 .4rem}
                  section > h2{font-size:.8rem;color:#8b96a6;text-transform:uppercase;letter-spacing:.06em;margin:0 0 .5rem}
                  footer{color:#6b7484;font-size:.78rem;margin-top:1.8rem;border-top:1px solid #2a313d;padding-top:.8rem}
                  .position-frame{stroke:#3a4252;stroke-width:1;fill:#161b23}
                  .position-axis{stroke:#3a4252;stroke-width:1;stroke-dasharray:3 3}
                  .position-average{stroke:#8b94a6;stroke-width:1;stroke-dasharray:5 4;opacity:.55}
                  .position-tick{fill:#9aa3b5;font-size:8px}
                  .position-quadrant{fill:#9aa3b5;font-size:8.5px;opacity:.8;letter-spacing:.04em;text-transform:uppercase}
                  .position-trail{fill:none;stroke:#8b94a6;stroke-width:1;stroke-dasharray:2 3;opacity:.5}
                  .position-trail-start{fill:#8b94a6;opacity:.6}
                  .position-dot{fill:rgba(120,180,255,.5);stroke:rgba(150,200,255,.9)}
                  .position-dot.own{fill:rgba(120,220,160,.55);stroke:rgba(140,240,180,.95)}
                  .position-name{fill:#c8cedb;font-size:9px}
                  .position-share{fill:#eef2f8;font-size:10px;font-weight:600}
                  /* D118: above twelve companies the bubbles are numbered and the names live here.
                     A grid, not columns+float: the floated share used to collide with the next item's
                     number and the legend read as one run-on line. */
                  .share-legend{display:grid;grid-template-columns:1fr 1fr;gap:.05rem 1.4rem;margin:.9rem 0 0;padding-left:1.5rem;font-size:.82rem;color:#c8cedb}
                  .share-legend li{margin:0}
                  .share-legend li b{margin-left:.4rem;color:#eef2f8}
                  .position-label{fill:#9aa3b5;font-size:9px}
                  .position-map{margin:0}
                  .position-map figcaption h3{margin:0 0 .15rem;font-size:1rem}
                  .position-map figcaption p{margin:0 0 .4rem;font-size:.9rem;color:#8b96a6}
                  .position-map svg{width:min(620px,92vw);height:auto;display:block;margin:0 auto}
                  /* D112/D121: one solid pie per product, side by side on a wide screen, stacked on a phone. */
                  .share-pies{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:1rem;margin:0 0 .8rem}
                  .share-pie{margin:0;background:#161b23;border:1px solid #2a313d;border-radius:10px;padding:.6rem}
                  .share-pie figcaption{font-size:.86rem;font-weight:600;margin:0 0 .35rem}
                  .share-pie svg{width:100%%;max-width:220px;height:auto;display:block;margin:0 auto}
                  .share-legend{width:100%%;border-collapse:collapse;font-size:.78rem;margin-top:.35rem}
                  .share-legend td{padding:.13rem .3rem;border-bottom:1px solid #232a35}
                  .share-legend td.num{text-align:right;font-variant-numeric:tabular-nums}
                  .share-legend th{font-weight:400;color:#8b96a6;font-size:.72rem;text-align:left;padding:.1rem .3rem}
                  .share-legend th.num{text-align:right}
                  .swatch{display:inline-block;width:.7rem;height:.7rem;border-radius:2px;vertical-align:middle}
                  .muted{color:#8b96a6}
                </style></head><body><main>
                  <h1>%s</h1><p class="sub">%s</p>
                  %s
                  <footer>%s</footer>
                </main></body></html>
                """.formatted(lang, escape(title), escape(title), escape(subtitle),
                String.join("\n", sections), escape(footer));
    }

    /** Brand index for the table, against the class average, as the chart's brand axis does. */
    private static double safeBrand(List<Company> companies, Company company) {
        double average = companies.stream().mapToDouble(Company::brand).average().orElse(0);
        return average > 0 ? company.brand() / average : 1;
    }

    /**
     * A caption string from the console's own dictionaries, carried in the template so that a shared page
     * says exactly what the console says — the same words, in the class language.
     */
    public String text(JsonNode template, String lang, String key) {
        JsonNode dict = template.path("strings").path("zh".equals(lang) ? "zh" : "en");
        return dict.path(key).asText(key);
    }

    /**
     * Replaces {@code token} occurrences left to right. The count must match, or one company's share would
     * be printed in another company's bubble — precisely the failure this class exists to prevent.
     */
    private static String substitute(String svg, String token, List<String> values) {
        int found = countOf(svg, token);
        if (found != values.size()) {
            throw new IllegalStateException(
                    "map template expects " + found + " figures but " + values.size() + " were supplied");
        }
        StringBuilder out = new StringBuilder(svg.length());
        int from = 0;
        int index = 0;
        while (true) {
            int at = svg.indexOf(token, from);
            if (at < 0) {
                out.append(svg, from, svg.length());
                return out.toString();
            }
            out.append(svg, from, at).append(values.get(index++));
            from = at + token.length();
        }
    }

    /** Removes the group that carries an unused slot, so the picture holds no empty bubble. */
    private static String dropGroup(String svg, String name) {
        int at = svg.indexOf(name);
        if (at < 0) {
            return svg;
        }
        int start = svg.lastIndexOf("<g>", at);
        int end = svg.indexOf("</g>", at);
        if (start < 0 || end < 0) {
            return svg.replace(name, "");
        }
        return svg.substring(0, start) + svg.substring(end + 4);
    }

    /** The share as the label prints it: one decimal, no sign (the template supplies the `%`). */
    private static String percentNumber(double share) {
        return BigDecimal.valueOf(share).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percent(double share) {
        return BigDecimal.valueOf(share).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Class averages keyed by product, from every company's lines. */
    public static Map<String, double[]> averages(Map<Long, List<Map<String, Object>>> all) {
        Map<String, double[]> totals = new LinkedHashMap<>();
        for (List<Map<String, Object>> lines : all.values()) {
            for (Map<String, Object> line : lines) {
                String product = String.valueOf(line.get("product"));
                double weight = weightOf(line);
                double[] entry = totals.computeIfAbsent(product, k -> new double[3]);
                entry[0] += number(line.get("price")) * weight;
                entry[1] += number(line.get("quality")) * weight;
                entry[2] += weight;
            }
        }
        Map<String, double[]> averages = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> entry : totals.entrySet()) {
            averages.put(entry.getKey(), new double[] {
                    entry.getValue()[2] > 0 ? entry.getValue()[0] / entry.getValue()[2] : 1,
                    entry.getValue()[2] > 0 ? entry.getValue()[1] / entry.getValue()[2] : 1});
        }
        return averages;
    }

    /** The index pair for one company's lines, against the class averages for the same products. */
    public static double[] indices(List<Map<String, Object>> lines, Map<String, double[]> averages) {
        double price = 0;
        double quality = 0;
        double weight = 0;
        for (Map<String, Object> line : lines) {
            double[] average = averages.get(String.valueOf(line.get("product")));
            if (average == null) {
                continue;
            }
            double w = weightOf(line);
            price += (number(line.get("price")) / average[0]) * w;
            quality += (number(line.get("quality")) / average[1]) * w;
            weight += w;
        }
        if (weight > 0) {
            return new double[] {price / weight, quality / weight};
        }
        double p = 0;
        double q = 0;
        int n = 0;
        for (Map<String, Object> line : lines) {
            double[] average = averages.get(String.valueOf(line.get("product")));
            if (average == null) {
                continue;
            }
            p += number(line.get("price")) / average[0];
            q += number(line.get("quality")) / average[1];
            n++;
        }
        return n == 0 ? new double[] {1, 1} : new double[] {p / n, q / n};
    }

    private static double weightOf(Map<String, Object> line) {
        double revenue = number(line.get("revenue"));
        return revenue > 0 ? revenue : Math.max(number(line.get("sold")), 0);
    }

    static double number(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }
}
