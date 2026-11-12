package com.bizsim.sim;

import com.bizsim.tenant.TenantContext;
import com.bizsim.web.ProblemException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Simulation templates: the economy, catalogue and settings a session is played on.
 *
 * <p>Ruling R2/D22: the shipped `standard-v1` stays global and read-only; a tenant copies it and
 * edits **the copy**. Editing is therefore limited to a template the tenant owns, and a copy is a
 * draft for exactly that reason.
 */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    public record CopyRequest(String name) {
    }

    public record Detail(String key, String name, Long tenantId, String status, String basedOn,
                         boolean editable, Map<String, Object> settings,
                         List<ScenarioRepository.RoundRow> environment,
                         List<ScenarioRepository.SegmentRow> segments,
                         List<ScenarioRepository.ProductRow> products,
                         List<ScenarioRepository.ChannelRow> channels,
                         List<ScenarioRepository.ProjectRow> projects,
                         List<Scenario.EventSpec> events) {
    }

    public record EventsRequest(List<Scenario.EventSpec> events) {
    }

    public record EnvironmentRequest(List<ScenarioRepository.RoundRow> rounds) {
    }

    public record ProductsRequest(List<ScenarioRepository.ProductRow> products) {
    }

    public record SegmentsRequest(List<ScenarioRepository.SegmentRow> segments) {
    }

    public record ChannelsRequest(List<ScenarioRepository.ChannelRow> channels) {
    }

    public record ProjectsRequest(List<ScenarioRepository.ProjectRow> projects) {
    }

    /**
     * The values the engine understands. These are the four branches in
     * {@code ScoringEngine} — anything else throws at round close, so the editor refuses it here
     * instead of letting a class discover it mid-session.
     */
    private static final List<String> ADVANTAGE_TYPES = List.of(
            "UNIT_COST_DOWN", "CAPACITY_UP", "QUALITY_UP", "ATTRACTIVENESS_UP");
    private static final List<String> BENEFIT_CURVES = List.of("LINEAR", "POWER", "CHANCE");
    private static final List<String> OVERFUNDING = List.of("REFUND", "LOST");

    private static final int MAX_ROUNDS = 48;

    private final ScenarioRepository scenarios;

    public TemplateController(ScenarioRepository scenarios) {
        this.scenarios = scenarios;
    }

    @GetMapping
    public List<ScenarioRepository.TemplateRow> list() {
        TenantContext.Principal me = requireStaff();
        return scenarios.listVisible(me.tenantId());
    }

    /** Everything the editor needs in one call: the editable settings and the environment timeline. */
    @GetMapping("/{key}")
    public Detail detail(@PathVariable String key) {
        TenantContext.Principal me = requireStaff();
        ScenarioRepository.TemplateRow row = scenarios.findVisible(me.tenantId(), key)
                .orElseThrow(() -> ProblemException.notFound("template.not_found", "no such template"));
        return detail(me, row);
    }

    @PostMapping("/{key}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    public ScenarioRepository.TemplateRow copy(@PathVariable String key,
                                               @RequestBody(required = false) CopyRequest request) {
        TenantContext.Principal me = requireBuilder();
        String copy = scenarios.copy(key, me.tenantId(), me.tenantCode(),
                request == null ? null : request.name());
        return scenarios.findVisible(me.tenantId(), copy).orElseThrow();
    }

    /** Merges the submitted settings into the copy. Unknown fields are ignored, never invented. */
    @PutMapping("/{key}/settings")
    public Detail saveSettings(@PathVariable String key, @RequestBody Map<String, Object> settings) {
        TenantContext.Principal me = requireBuilder();
        ScenarioRepository.TemplateRow row = requireEditable(me, key);
        validateSettings(settings);
        scenarios.saveSettings(key, settings);
        return detail(me, row);
    }

    /** Replaces the environment timeline: positions are a table, so a partial edit has no meaning. */
    @PutMapping("/{key}/environment")
    public Detail saveEnvironment(@PathVariable String key, @RequestBody EnvironmentRequest request) {
        TenantContext.Principal me = requireBuilder();
        ScenarioRepository.TemplateRow row = requireEditable(me, key);
        List<ScenarioRepository.RoundRow> rounds = request == null ? null : request.rounds();
        validateEnvironment(rounds);
        scenarios.saveEnvironment(key, rounds);
        return detail(me, row);
    }

    /** Replaces the product catalogue. Refused once the template has sessions: they name these codes. */
    @PutMapping("/{key}/products")
    public Detail saveProducts(@PathVariable String key, @RequestBody ProductsRequest request) {
        TenantContext.Principal me = requireBuilder();
        ScenarioRepository.TemplateRow row = requireEditable(me, key);
        requireUnused(me, key);
        List<ScenarioRepository.ProductRow> products = request == null ? null : request.products();
        validateProducts(products);
        validateSegmentTargets(products, scenarios.segments(key));
        scenarios.saveProducts(key, products);
        return detail(me, row);
    }

    /**
     * Replaces the customer classes (D81). Refused once the template has sessions, for the same
     * reason as the product catalogue: the class is what every round's market is computed from, so
     * editing it mid-run would rewrite a class that has already played (D84).
     */
    @PutMapping("/{key}/segments")
    public Detail saveSegments(@PathVariable String key, @RequestBody SegmentsRequest request) {
        TenantContext.Principal me = requireBuilder();
        ScenarioRepository.TemplateRow row = requireEditable(me, key);
        requireUnused(me, key);
        List<ScenarioRepository.SegmentRow> segments = request == null ? null : request.segments();
        validateSegments(segments, scenarios.products(key));
        scenarios.saveSegments(key, segments);
        return detail(me, row);
    }

    /**
     * Replaces the event deck. Unlike the catalogue this stays editable after a session exists: the
     * deck is drawn once per run and frozen into that run (D18), so an edit cannot reach back into a
     * class that already played.
     */
    @PutMapping("/{key}/events")
    public Detail saveEvents(@PathVariable String key, @RequestBody EventsRequest request) {
        TenantContext.Principal me = requireBuilder();
        ScenarioRepository.TemplateRow row = requireEditable(me, key);
        List<Scenario.EventSpec> events = request == null ? null : request.events();
        validateEvents(events, key, scenarios.segments(key));
        scenarios.saveEvents(key, events);
        return detail(me, row);
    }

    @PutMapping("/{key}/channels")
    public Detail saveChannels(@PathVariable String key, @RequestBody ChannelsRequest request) {
        TenantContext.Principal me = requireBuilder();
        ScenarioRepository.TemplateRow row = requireEditable(me, key);
        requireUnused(me, key);
        List<ScenarioRepository.ChannelRow> channels = request == null ? null : request.channels();
        validateChannels(channels);
        scenarios.saveChannels(key, channels);
        return detail(me, row);
    }

    @PutMapping("/{key}/projects")
    public Detail saveProjects(@PathVariable String key, @RequestBody ProjectsRequest request) {
        TenantContext.Principal me = requireBuilder();
        ScenarioRepository.TemplateRow row = requireEditable(me, key);
        requireUnused(me, key);
        List<ScenarioRepository.ProjectRow> projects = request == null ? null : request.projects();
        validateProjects(projects, scenarios.products(key));
        scenarios.saveProjects(key, projects);
        return detail(me, row);
    }

    // ------------------------------------------------------------------ rules

    /**
     * A template with sessions must not have its catalogue rewritten: a run's companies were created
     * from these codes and every stored decision names them. Settings and the environment stay
     * editable, because a run freezes its own economy (D18) and copies its own numbers.
     */
    private void requireUnused(TenantContext.Principal me, String key) {
        if (scenarios.hasRuns(me.tenantId(), key)) {
            throw ProblemException.conflict("template.in_use",
                    "this template already has simulations; copy it to change its catalogue",
                    "template", key);
        }
    }

    /**
     * The editor may only touch a copy the tenant owns. The shipped template is a reference everyone
     * plays on; letting one class edit it would rewrite every other class's economy.
     */
    private ScenarioRepository.TemplateRow requireEditable(TenantContext.Principal me, String key) {
        ScenarioRepository.TemplateRow row = scenarios.findVisible(me.tenantId(), key)
                .orElseThrow(() -> ProblemException.notFound("template.not_found", "no such template"));
        if (row.tenantId() == null) {
            throw ProblemException.conflict("template.read_only",
                    "the shipped template is read-only; copy it first");
        }
        return row;
    }

    private static void validateSettings(Map<String, Object> settings) {
        if (settings == null) {
            throw ProblemException.badRequest("template.settings_invalid", "settings are required",
                    "field", "settings");
        }
        requireRange(settings, "rounds", 1, MAX_ROUNDS);
        requireRange(settings, "roundMinutes", 1, 480);
        requireRange(settings, "cycles", 1, 12);
        requireRange(settings, "cycleLength", 1, MAX_ROUNDS);
        requireMinimum(settings, "startingCash", BigDecimal.ZERO);
        requireMinimum(settings, "startingBrand", BigDecimal.ZERO);
        requireMinimum(settings, "fixedCostPerMonth", BigDecimal.ZERO);
    }

    private static void requireRange(Map<String, Object> settings, String field, int min, int max) {
        Object raw = settings.get(field);
        if (raw == null) {
            return; // absent means "leave as it is"
        }
        int value;
        try {
            value = new BigDecimal(String.valueOf(raw)).intValueExact();
        } catch (RuntimeException e) {
            throw ProblemException.badRequest("template.settings_invalid",
                    field + " must be a whole number", "field", field);
        }
        if (value < min || value > max) {
            throw ProblemException.badRequest("template.settings_invalid",
                    field + " must be between " + min + " and " + max,
                    "field", field, "min", min, "max", max);
        }
    }

    private static void requireMinimum(Map<String, Object> settings, String field, BigDecimal min) {
        Object raw = settings.get(field);
        if (raw == null) {
            return;
        }
        try {
            if (new BigDecimal(String.valueOf(raw)).compareTo(min) < 0) {
                throw ProblemException.badRequest("template.settings_invalid",
                        field + " cannot be below " + min.toPlainString(),
                        "field", field, "min", min.toPlainString());
            }
        } catch (NumberFormatException e) {
            throw ProblemException.badRequest("template.settings_invalid",
                    field + " must be a number", "field", field);
        }
    }

    private static void validateProducts(List<ScenarioRepository.ProductRow> products) {
        if (products == null || products.isEmpty()) {
            throw catalogInvalid("a template needs at least one product", "field", "products");
        }
        if (products.size() > 10) {
            throw catalogInvalid("a template cannot exceed 10 products", "field", "products", "max", 10);
        }
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (ScenarioRepository.ProductRow product : products) {
            if (product == null || isBlank(product.code())) {
                throw catalogInvalid("every product needs a code", "field", "code");
            }
            if (!codes.add(product.code())) {
                throw catalogInvalid("product codes must be unique: " + product.code(),
                        "field", "code", "code", product.code());
            }
            if (isBlank(product.name())) {
                throw catalogInvalid("product " + product.code() + " needs a name",
                        "field", "name", "code", product.code());
            }
            requirePositive(product.basePrice(), "basePrice", product.code());
            requirePositive(product.baseUnitCost(), "baseUnitCost", product.code());
            requirePositive(product.unitsPerWorker(), "unitsPerWorker", product.code());
        }
    }

    private static void validateChannels(List<ScenarioRepository.ChannelRow> channels) {
        if (channels == null || channels.isEmpty()) {
            throw catalogInvalid("a template needs at least one channel", "field", "channels");
        }
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (ScenarioRepository.ChannelRow channel : channels) {
            if (channel == null || isBlank(channel.code())) {
                throw catalogInvalid("every channel needs a code", "field", "code");
            }
            if (!codes.add(channel.code())) {
                throw catalogInvalid("channel codes must be unique: " + channel.code(),
                        "field", "code", "code", channel.code());
            }
            requirePositive(channel.reachWeight(), "reachWeight", channel.code());
        }
    }

    private static void validateProjects(List<ScenarioRepository.ProjectRow> projects,
                                         List<ScenarioRepository.ProductRow> products) {
        if (projects == null || projects.isEmpty()) {
            throw catalogInvalid("a template needs at least one research project", "field", "projects");
        }
        java.util.Set<String> codes = new java.util.HashSet<>();
        java.util.Set<String> productCodes = new java.util.HashSet<>();
        for (ScenarioRepository.ProductRow product : products) {
            productCodes.add(product.code());
        }
        for (ScenarioRepository.ProjectRow project : projects) {
            if (project == null || isBlank(project.code())) {
                throw catalogInvalid("every project needs a code", "field", "code");
            }
            if (!codes.add(project.code())) {
                throw catalogInvalid("project codes must be unique: " + project.code(),
                        "field", "code", "code", project.code());
            }
            if (!ADVANTAGE_TYPES.contains(project.advantageType())) {
                throw catalogInvalid(project.code() + ": advantageType must be one of " + ADVANTAGE_TYPES,
                        "field", "advantageType", "code", project.code());
            }
            if (!BENEFIT_CURVES.contains(project.benefitCurve())) {
                throw catalogInvalid(project.code() + ": benefitCurve must be one of " + BENEFIT_CURVES,
                        "field", "benefitCurve", "code", project.code());
            }
            if (!OVERFUNDING.contains(project.overfunding())) {
                throw catalogInvalid(project.code() + ": overfunding must be one of " + OVERFUNDING,
                        "field", "overfunding", "code", project.code());
            }
            if (project.durationMonths() < 1) {
                throw catalogInvalid(project.code() + ": durationMonths must be at least 1",
                        "field", "durationMonths", "code", project.code());
            }
            if (!isBlank(project.targetProduct()) && !productCodes.contains(project.targetProduct())) {
                throw catalogInvalid(project.code() + ": unknown target product " + project.targetProduct(),
                        "field", "targetProduct", "code", project.code());
            }
            requirePositive(project.advantageValue(), "advantageValue", project.code());
        }
    }

    private static void requirePositive(java.math.BigDecimal value, String field, String code) {
        if (value == null || value.signum() <= 0) {
            throw catalogInvalid(code + ": " + field + " must be above zero",
                    "field", field, "code", code);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ProblemException catalogInvalid(String message, Object... params) {
        return ProblemException.badRequest("template.catalog_invalid", message, params);
    }

    /** A product may only target a class the template actually has. */
    private static void validateSegmentTargets(List<ScenarioRepository.ProductRow> products,
                                               List<ScenarioRepository.SegmentRow> segments) {
        Set<String> codes = new java.util.LinkedHashSet<>();
        for (ScenarioRepository.SegmentRow segment : segments) {
            codes.add(segment.code());
        }
        for (ScenarioRepository.ProductRow product : products) {
            if (!isBlank(product.targetSegment()) && !codes.contains(product.targetSegment())) {
                throw ProblemException.badRequest("template.catalog_invalid",
                        product.code() + ": unknown customer class " + product.targetSegment(),
                        "field", "targetSegment", "code", product.code());
            }
            if (product.crossSegmentPct() != null && (product.crossSegmentPct().signum() < 0
                    || product.crossSegmentPct().compareTo(BigDecimal.ONE) > 0)) {
                throw ProblemException.badRequest("template.catalog_invalid",
                        product.code() + ": crossSegmentPct must be between 0 and 1",
                        "field", "crossSegmentPct", "code", product.code());
            }
            validateTexts(product.texts(), product.code());
        }
    }

    /**
     * D87: a name per language, one row per language, two lowercase letters. A language without a row
     * is not an error — the workbench falls back to the authored name.
     */
    private static void validateTexts(List<ScenarioRepository.TextRow> texts, String subject) {
        if (texts == null) {
            return;
        }
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (ScenarioRepository.TextRow text : texts) {
            if (text == null || isBlank(text.name())) {
                continue; // an empty row is a row the builder has not filled in yet
            }
            String lang = isBlank(text.lang()) ? "" : text.lang().trim().toLowerCase(java.util.Locale.ROOT);
            if (!lang.matches("[a-z]{2}")) {
                throw ProblemException.badRequest("template.catalog_invalid",
                        subject + ": a language is two lowercase letters", "field", "texts.lang",
                        "code", subject);
            }
            if (!seen.add(lang)) {
                throw ProblemException.badRequest("template.catalog_invalid",
                        subject + ": " + lang + " appears more than once", "field", "texts.lang",
                        "code", subject);
            }
        }
    }

    /**
     * The classes a product can serve. Two rules matter: a class a product points at cannot vanish
     * (the product would silently fall back to the legacy pool), and a class must have a positive
     * size and purchasing power — a zero would make its market disappear with no explanation.
     */
    private static void validateSegments(List<ScenarioRepository.SegmentRow> segments,
                                         List<ScenarioRepository.ProductRow> products) {
        if (segments == null || segments.isEmpty()) {
            throw ProblemException.badRequest("template.segments_invalid",
                    "a template needs at least one customer class", "field", "segments");
        }
        Set<String> codes = new java.util.LinkedHashSet<>();
        for (ScenarioRepository.SegmentRow segment : segments) {
            if (segment == null || isBlank(segment.code()) || isBlank(segment.name())) {
                throw ProblemException.badRequest("template.segments_invalid",
                        "every customer class needs a code and a name", "field", "code");
            }
            if (!codes.add(segment.code())) {
                throw ProblemException.badRequest("template.segments_invalid",
                        "duplicate customer class " + segment.code(), "field", "code",
                        "code", segment.code());
            }
            requirePositive(segment.baseSize(), "baseSize", segment.code());
            requirePositive(segment.basePurchasingPower(), "basePurchasingPower", segment.code());
            validateTexts(segment.texts(), segment.code());
            if (segment.incomeElasticity() == null || segment.incomeElasticity().signum() < 0) {
                throw ProblemException.badRequest("template.segments_invalid",
                        segment.code() + ": incomeElasticity must be 0 or more",
                        "field", "incomeElasticity", "code", segment.code());
            }
            if (segment.trendAffinity() == null
                    || segment.trendAffinity().abs().compareTo(BigDecimal.ONE) > 0) {
                throw ProblemException.badRequest("template.segments_invalid",
                        segment.code() + ": trendAffinity must be between -1 and 1",
                        "field", "trendAffinity", "code", segment.code());
            }
        }
        for (ScenarioRepository.ProductRow product : products) {
            if (!isBlank(product.targetSegment()) && !codes.contains(product.targetSegment())) {
                throw ProblemException.badRequest("template.segments_invalid",
                        "product " + product.code() + " targets a customer class that is not in the list: "
                                + product.targetSegment(), "field", "targetSegment",
                        "code", product.code());
            }
        }
    }

    private static void validateEvents(List<Scenario.EventSpec> events, String key,
                                       List<ScenarioRepository.SegmentRow> segments) {
        if (events == null) {
            return; // an empty deck is legitimate: some economies have no shocks
        }
        Set<String> classes = new java.util.LinkedHashSet<>();
        for (ScenarioRepository.SegmentRow segment : segments) {
            classes.add(segment.code());
        }
        Set<String> codes = new java.util.LinkedHashSet<>();
        for (Scenario.EventSpec event : events) {
            if (event == null || isBlank(event.code()) || isBlank(event.name())) {
                throw ProblemException.badRequest("template.events_invalid",
                        "every event needs a code and a name", "field", "code");
            }
            String lang = isBlank(event.lang()) ? "en"
                    : event.lang().trim().toLowerCase(java.util.Locale.ROOT);
            if (!lang.matches("[a-z]{2}")) {
                throw ProblemException.badRequest("template.events_invalid",
                        "event " + event.code() + ": a language is two lowercase letters",
                        "field", "lang", "code", event.code());
            }
            // D86: a code is unique inside its language — the same card exists once per set.
            if (!codes.add(lang + ":" + event.code())) {
                throw ProblemException.badRequest("template.events_invalid",
                        "duplicate event code " + event.code() + " in " + lang,
                        "field", "code", "code", event.code());
            }
            if (event.earliestRound() < 1 || event.latestRound() < event.earliestRound()) {
                throw ProblemException.badRequest("template.events_invalid",
                        "event " + event.code() + " has an impossible round window",
                        "field", "earliestRound", "code", event.code());
            }
            if (event.probability() == null || event.probability().signum() < 0
                    || event.probability().compareTo(BigDecimal.ONE) > 0) {
                throw ProblemException.badRequest("template.events_invalid",
                        "event " + event.code() + " needs a probability between 0 and 1",
                        "field", "probability", "code", event.code());
            }
            if (event.durationMonths() < 1 || event.durationMonths() > 12) {
                throw ProblemException.badRequest("template.events_invalid",
                        "event " + event.code() + " lasts 1 to 12 months",
                        "field", "durationMonths", "code", event.code());
            }
            if (!isBlank(event.category())
                    && !ScenarioRepository.EVENT_CATEGORIES.contains(event.category())) {
                throw ProblemException.badRequest("template.events_invalid",
                        "event " + event.code() + ": category must be one of "
                                + ScenarioRepository.EVENT_CATEGORIES,
                        "field", "category", "code", event.code());
            }
            Set<String> affected = new java.util.LinkedHashSet<>();
            for (Scenario.EventEffect effect : event.effects() == null
                    ? List.<Scenario.EventEffect>of() : event.effects()) {
                if (effect == null || isBlank(effect.segmentCode())) {
                    throw ProblemException.badRequest("template.events_invalid",
                            "event " + event.code() + ": an effect needs a customer class",
                            "field", "segmentCode", "code", event.code());
                }
                if (!affected.add(effect.segmentCode())) {
                    throw ProblemException.badRequest("template.events_invalid",
                            "event " + event.code() + ": " + effect.segmentCode()
                                    + " appears more than once",
                            "field", "segmentCode", "code", event.code());
                }
                if (!classes.contains(effect.segmentCode())) {
                    throw ProblemException.badRequest("template.events_invalid",
                            "event " + event.code() + ": no customer class " + effect.segmentCode(),
                            "field", "segmentCode", "code", event.code());
                }
                requirePositive(effect.sizeMultiplier(), "sizeMultiplier", event.code());
                requirePositive(effect.purchasingPowerMultiplier(), "purchasingPowerMultiplier",
                        event.code());
            }
            for (String field : List.of("demandMultiplier", "costMultiplier", "wageMultiplier")) {
                BigDecimal value = switch (field) {
                    case "demandMultiplier" -> event.demandMultiplier();
                    case "costMultiplier" -> event.costMultiplier();
                    default -> event.wageMultiplier();
                };
                if (value == null || value.signum() <= 0) {
                    throw ProblemException.badRequest("template.events_invalid",
                            "event " + event.code() + ": " + field + " must be above zero",
                            "field", field, "code", event.code());
                }
            }
        }
    }

    private static void validateEnvironment(List<ScenarioRepository.RoundRow> rounds) {
        if (rounds == null || rounds.isEmpty()) {
            throw ProblemException.badRequest("template.environment_invalid",
                    "the environment needs at least one round", "field", "rounds");
        }
        // D86: each language carries its own cycle, numbered 1..n without gaps within that language.
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        Map<String, java.util.Set<Integer>> seen = new java.util.LinkedHashMap<>();
        for (ScenarioRepository.RoundRow row : rounds) {
            if (row == null || row.round() < 1) {
                throw ProblemException.badRequest("template.environment_invalid",
                        "every round needs a position", "field", "round");
            }
            // A client written before D86 sends no language; that is the base set.
            String lang = isBlank(row.lang()) ? "en"
                    : row.lang().trim().toLowerCase(java.util.Locale.ROOT);
            if (!lang.matches("[a-z]{2}")) {
                throw ProblemException.badRequest("template.environment_invalid",
                        "a language is two lowercase letters, not " + row.lang(), "field", "lang");
            }
            if (!seen.computeIfAbsent(lang, k -> new java.util.LinkedHashSet<>()).add(row.round())) {
                throw ProblemException.badRequest("template.environment_invalid",
                        "round " + row.round() + " appears twice in " + lang,
                        "field", "round", "round", row.round());
            }
            counts.merge(lang, 1, Integer::sum);
            for (String index : List.of("demandIndex", "costIndex", "wageIndex")) {
                BigDecimal value = switch (index) {
                    case "demandIndex" -> row.demandIndex();
                    case "costIndex" -> row.costIndex();
                    default -> row.wageIndex();
                };
                if (value == null || value.signum() <= 0) {
                    throw ProblemException.badRequest("template.environment_invalid",
                            index + " must be above zero on round " + row.round() + " (" + lang + ")",
                            "field", index, "round", row.round());
                }
            }
        }
        for (Map.Entry<String, java.util.Set<Integer>> entry : seen.entrySet()) {
            int size = entry.getValue().size();
            for (int position = 1; position <= size; position++) {
                if (!entry.getValue().contains(position)) {
                    throw ProblemException.badRequest("template.environment_invalid",
                            "rounds must be numbered 1..n without gaps (" + entry.getKey()
                                    + " is missing " + position + ")",
                            "field", "round", "lang", entry.getKey());
                }
            }
            if (size > MAX_ROUNDS) {
                throw ProblemException.badRequest("template.environment_invalid",
                        "the environment cannot exceed " + MAX_ROUNDS + " rounds",
                        "field", "rounds", "max", MAX_ROUNDS);
            }
        }
        requireSameLength(counts);
    }

    /** Every language cycle present must be the same length, or one class would run short. */
    private static void requireSameLength(Map<String, Integer> counts) {
        int first = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (first < 0) {
                first = entry.getValue();
            } else if (entry.getValue() != first) {
                throw ProblemException.badRequest("template.environment_invalid",
                        "every language cycle must be the same length (" + entry.getKey() + " has "
                                + entry.getValue() + ", another has " + first + ")",
                        "field", "rounds", "lang", entry.getKey());
            }
        }
    }

    private Detail detail(TenantContext.Principal me, ScenarioRepository.TemplateRow row) {
        return new Detail(row.key(), row.name(), row.tenantId(), row.status(), row.basedOn(),
                row.tenantId() != null && row.tenantId() == me.tenantId(),
                scenarios.settings(row.key()), scenarios.environment(row.key()),
                scenarios.segments(row.key()), scenarios.products(row.key()),
                scenarios.channels(row.key()),
                scenarios.projects(row.key()), scenarios.eventsOf(row.key()));
    }

    /** Building is a separate job from running (ruling 3 Nov 2026): a facilitator consumes, never edits. */
    private static TenantContext.Principal requireBuilder() {
        TenantContext.Principal me = TenantContext.require();
        if (!"BUILDER".equals(me.role()) && !"ADMIN".equals(me.role())) {
            throw ProblemException.forbidden("auth.builder_only",
                    "only a builder prepares templates; ask your administrator");
        }
        return me;
    }

    /** Reading is open to staff: a facilitator must see the economy they are about to run. */
    private static TenantContext.Principal requireStaff() {
        TenantContext.Principal me = TenantContext.require();
        if (!"BUILDER".equals(me.role()) && !"FACILITATOR".equals(me.role())
                && !"ADMIN".equals(me.role())) {
            throw ProblemException.forbidden("auth.facilitator_only", "staff only");
        }
        return me;
    }
}
