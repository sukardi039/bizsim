package com.bizsim.sim;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bizsim.run.RunRepository;
import com.bizsim.web.ProblemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D102: the position map as something a class can hold — a link, and the page behind it.
 *
 * <p>Two questions from a real debrief drove this. *"The chart is hard to read"* — so the shared page
 * prints the same figure the console draws, larger, with a table of the numbers beside it. *"How do we
 * review earlier rounds?"* — so the page is always the **whole run so far**: one chart per computed round,
 * oldest first, which is what a facilitator needs when a class asks "why is my position where it is?".
 *
 * <p>Access rules are the session's own: a facilitator shares any round, a trainee shares their own run
 * (the class figures are already on their standings screen), and the page itself needs no account at all.
 */
@Service
public class MapShareService {

    private final RoundRepository rounds;
    private final RunRepository runs;
    private final PositionMapPage renderer;
    private final MapShareRepository shares;

    public MapShareService(RoundRepository rounds, RunRepository runs, PositionMapPage renderer,
                           MapShareRepository shares) {
        this.rounds = rounds;
        this.runs = runs;
        this.renderer = renderer;
        this.shares = shares;
    }

    public record ShareLink(long id, String token, String url, String title, Integer round, Instant expiresAt) {
    }

    /**
     * Every computed round of the run, oldest first, with the products each company sold.
     *
     * <p>Built once per request from stored statements: nothing is recalculated, so a shared page cannot
     * disagree with the numbers a class was shown at the time.
     */
    public List<PositionMapPage.RoundInput> history(long runId, Integer upTo) {
        Map<Integer, Map<Long, List<Map<String, Object>>>> byRound = new LinkedHashMap<>();
        for (RoundRepository.TeamState team : rounds.teams(runId)) {
            for (Map<String, Object> statement : rounds.statements(runId, team.id())) {
                int round = ((Number) statement.get("round")).intValue();
                if (upTo != null && round > upTo) {
                    continue;
                }
                Object detail = statement.get("detail");
                List<Map<String, Object>> lines = lines(detail);
                byRound.computeIfAbsent(round, key -> new LinkedHashMap<>())
                        .put(team.id(), lines);
            }
        }

        List<PositionMapPage.RoundInput> result = new ArrayList<>();
        for (Integer round : byRound.keySet().stream().sorted().toList()) {
            List<Map<String, Object>> scoreboard = rounds.scoreboard(runId, round);
            result.add(new PositionMapPage.RoundInput(scoreboard, byRound.get(round)));
        }
        return result;
    }

    /**
     * D112: the per-product market-share pies for one round of the run, as HTML.
     *
     * <p><strong>Where the served market comes from.</strong> The allocator divides `pool × (1 −
     * baselineShare)` and hands every piece to some company, so the sum of the companies' `demand` for a
     * product **is** the served market — not an approximation of it. Reading it back from the statements
     * avoids recomputing the season, the cohort scale and the segment carve in a second place, which is
     * how two implementations of the same number start to disagree.
     */
    /** Renders one round's pies for the shared page, from the same data the console is given. */
    private String piesFor(PositionMapPage.RoundInput round, String lang) {
        List<MarketSharePie.Pie> pies = piesOf(round);
        return MarketShareDonut.renderAll(lang, pies, "", text(lang, "debrief.unserved"),
                text(lang, "debrief.noMarket"), text(lang, "debrief.coverage"));
    }

    /** A label from the frozen template's strings, or an empty string when it has none. */
    private String text(String lang, String key) {
        try {
            JsonNode strings = renderer.template().path("strings").path(lang);
            JsonNode value = strings.get(key);
            return value == null ? "" : value.asText();
        } catch (RuntimeException e) {
            return "";
        }
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

    /** The product lines out of a statement's `detail`, or an empty list when a round stored none. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(Object detail) {
        if (detail instanceof Map<?, ?> map) {
            Object products = ((Map<String, Object>) map).get("products");
            if (products instanceof List<?> list) {
                List<Map<String, Object>> lines = new ArrayList<>();
                for (Object entry : list) {
                    if (entry instanceof Map<?, ?> line) {
                        lines.add((Map<String, Object>) line);
                    }
                }
                return lines;
            }
        }
        return List.of();
    }

    /**
     * Renders the whole run as one page: a section per computed round, the last one first, so the most
     * recent picture is what a reader sees when the link opens.
     */
    public String pageFor(long tenantId, long runId, Integer upTo, String title, String footer) {
        runs.find(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
        // The class language the package froze (D86) — not the reader's browser, which has no session.
        String lang = runs.localeOf(runId) == null ? "en" : runs.localeOf(runId);

        List<PositionMapPage.RoundInput> history = history(runId, upTo);
        if (history.isEmpty()) {
            throw ProblemException.badRequest("map.no_rounds", "no round of this session has been computed yet");
        }

        List<String> sections = new ArrayList<>();
        // Newest first: the reader opened the link to see where things stand.
        for (int index = history.size() - 1; index >= 0; index--) {
            PositionMapPage.RoundInput round = history.get(index);
            boolean brand = "brand".equals(renderer.axisFor(history.subList(0, index + 1)));
            List<PositionMapPage.Company> companies = renderer.positionsOf(round);
            // D118: never a partial chart. A class larger than the picture used to be silently truncated,
            // which is a shared page that names all thirty companies in its table and draws six of them —
            // a reader cannot tell that from a class where the rest scored nothing. Refuse, and name the
            // command that fixes it; the template is built for the ceiling (thirty) by `npm run map-template`.
            if (companies.size() > renderer.slots()) {
                throw new IllegalStateException("the shared map draws up to " + renderer.slots()
                        + " companies but this class has " + companies.size()
                        + "; rebuild the map template for the ceiling (npm run map-template in web/)");
            }
            String caption = renderer.roundCaption(lang, round);
            // D112: the pies come first — a facilitator opening the link to ask "who holds this market"
            // should not have to scroll past the position map to find out.
            sections.add("<section><h2>" + renderer.escape(caption) + "</h2>"
                    + piesFor(round, lang)
                    + renderer.figure(lang, companies, brand) + "</section>");
        }

        String heading = title == null || title.isBlank()
                ? renderer.text(renderer.template(), lang, "debrief.positionMap")
                : title;
        String subtitle = renderer.text(renderer.template(), lang, "debrief.positionMapHint");
        return renderer.runPage(heading, subtitle, lang, sections, footer);
    }

    /** Freezes the current picture into a row and returns the link. */
    @Transactional
    public ShareLink share(long tenantId, long runId, Integer round, String title, String baseUrl) {
        String html = pageFor(tenantId, runId, round, title,
                "bizsim · " + Instant.now().toString().substring(0, 10));
        MapShareRepository.Share share = shares.create(tenantId, runId, "position-map", round, title, html);
        return new ShareLink(share.id(), share.token(),
                baseUrl + "/share/map/" + share.token(), title, round, share.expiresAt());
    }

    /**
     * D112: the same pies the shared page draws, as data for the facilitator's console.
     *
     * <p>Returned from this service rather than rebuilt in a controller so there is **one** definition of
     * what a slice is: the console and the shared page cannot disagree about a company's share, because
     * they are handed the same {@link MarketSharePie.Pie} objects.
     */
    public List<MarketSharePie.Pie> pies(long tenantId, long runId, Integer round) {
        runs.find(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
        List<PositionMapPage.RoundInput> history = history(runId, round);
        if (history.isEmpty()) {
            return List.of();
        }
        return piesOf(history.get(history.size() - 1));
    }

    /** The pie data for one round, without rendering it. */
    private List<MarketSharePie.Pie> piesOf(PositionMapPage.RoundInput round) {
        List<String> products = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();
        List<MarketSharePie.Company> companies = new ArrayList<>();
        Map<String, BigDecimal> served = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Map<String, Object>>> entry : round.linesByTeam().entrySet()) {
            String name = null;
            boolean auto = false;
            String position = null;
            for (Map<String, Object> row : round.scoreboard()) {
                if (String.valueOf(entry.getKey()).equals(String.valueOf(row.get("team_id")))) {
                    name = String.valueOf(row.get("company_name"));
                    auto = Boolean.TRUE.equals(row.get("auto_player"));
                    Object cell = row.get("auto_strategy");
                    position = cell == null ? null : String.valueOf(cell);
                    break;
                }
            }
            if (name == null) {
                name = "Company " + entry.getKey();
            }
            companies.add(new MarketSharePie.Company(entry.getKey(), name, auto, position,
                    entry.getValue()));
            for (Map<String, Object> line : entry.getValue()) {
                String product = String.valueOf(line.get("product"));
                if (!products.contains(product)) {
                    products.add(product);
                    Object label = line.get("productName");
                    names.put(product, label == null ? product : String.valueOf(label));
                }
                served.merge(product, decimal(line.get("demand")), BigDecimal::add);
            }
        }
        java.util.Collections.sort(products);
        return MarketSharePie.build(products, names, served, companies);
    }

    public MapShareRepository.Share find(String token) {
        return shares.find(token).orElse(null);
    }
}
