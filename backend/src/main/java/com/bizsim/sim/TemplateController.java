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
                         List<ScenarioRepository.RoundRow> environment) {
    }

    public record EnvironmentRequest(List<ScenarioRepository.RoundRow> rounds) {
    }

    private static final int MAX_ROUNDS = 48;

    private final ScenarioRepository scenarios;

    public TemplateController(ScenarioRepository scenarios) {
        this.scenarios = scenarios;
    }

    @GetMapping
    public List<ScenarioRepository.TemplateRow> list() {
        TenantContext.Principal me = requireFacilitator();
        return scenarios.listVisible(me.tenantId());
    }

    /** Everything the editor needs in one call: the editable settings and the environment timeline. */
    @GetMapping("/{key}")
    public Detail detail(@PathVariable String key) {
        TenantContext.Principal me = requireFacilitator();
        ScenarioRepository.TemplateRow row = scenarios.findVisible(me.tenantId(), key)
                .orElseThrow(() -> ProblemException.notFound("template.not_found", "no such template"));
        return detail(me, row);
    }

    @PostMapping("/{key}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    public ScenarioRepository.TemplateRow copy(@PathVariable String key,
                                               @RequestBody(required = false) CopyRequest request) {
        TenantContext.Principal me = requireFacilitator();
        String copy = scenarios.copy(key, me.tenantId(), me.tenantCode(),
                request == null ? null : request.name());
        return scenarios.findVisible(me.tenantId(), copy).orElseThrow();
    }

    /** Merges the submitted settings into the copy. Unknown fields are ignored, never invented. */
    @PutMapping("/{key}/settings")
    public Detail saveSettings(@PathVariable String key, @RequestBody Map<String, Object> settings) {
        TenantContext.Principal me = requireFacilitator();
        ScenarioRepository.TemplateRow row = requireEditable(me, key);
        validateSettings(settings);
        scenarios.saveSettings(key, settings);
        return detail(me, row);
    }

    /** Replaces the environment timeline: positions are a table, so a partial edit has no meaning. */
    @PutMapping("/{key}/environment")
    public Detail saveEnvironment(@PathVariable String key, @RequestBody EnvironmentRequest request) {
        TenantContext.Principal me = requireFacilitator();
        ScenarioRepository.TemplateRow row = requireEditable(me, key);
        List<ScenarioRepository.RoundRow> rounds = request == null ? null : request.rounds();
        validateEnvironment(rounds);
        scenarios.saveEnvironment(key, rounds);
        return detail(me, row);
    }

    // ------------------------------------------------------------------ rules

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

    private static void validateEnvironment(List<ScenarioRepository.RoundRow> rounds) {
        if (rounds == null || rounds.isEmpty()) {
            throw ProblemException.badRequest("template.environment_invalid",
                    "the environment needs at least one round", "field", "rounds");
        }
        if (rounds.size() > MAX_ROUNDS) {
            throw ProblemException.badRequest("template.environment_invalid",
                    "the environment cannot exceed " + MAX_ROUNDS + " rounds",
                    "field", "rounds", "max", MAX_ROUNDS);
        }
        for (int i = 0; i < rounds.size(); i++) {
            ScenarioRepository.RoundRow row = rounds.get(i);
            if (row == null || row.round() != i + 1) {
                throw ProblemException.badRequest("template.environment_invalid",
                        "rounds must be numbered 1..n without gaps", "field", "round");
            }
            for (String index : List.of("demandIndex", "costIndex", "wageIndex")) {
                BigDecimal value = switch (index) {
                    case "demandIndex" -> row.demandIndex();
                    case "costIndex" -> row.costIndex();
                    default -> row.wageIndex();
                };
                if (value == null || value.signum() <= 0) {
                    throw ProblemException.badRequest("template.environment_invalid",
                            index + " must be above zero on round " + row.round(),
                            "field", index, "round", row.round());
                }
            }
        }
    }

    private Detail detail(TenantContext.Principal me, ScenarioRepository.TemplateRow row) {
        return new Detail(row.key(), row.name(), row.tenantId(), row.status(), row.basedOn(),
                row.tenantId() != null && row.tenantId() == me.tenantId(),
                scenarios.settings(row.key()), scenarios.environment(row.key()));
    }

    private static TenantContext.Principal requireFacilitator() {
        TenantContext.Principal me = TenantContext.require();
        if (!"FACILITATOR".equals(me.role()) && !"ADMIN".equals(me.role())) {
            throw ProblemException.forbidden("auth.facilitator_only", "facilitator only");
        }
        return me;
    }
}
