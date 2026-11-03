package com.bizsim.run;

import com.bizsim.sim.ScenarioRepository;
import com.bizsim.tenant.TenantContext;
import com.bizsim.web.ProblemException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Simulation packages — the seam between building and running (ruling of 3 Nov 2026).
 *
 * <p>Two parties, two jobs. A **builder** prepares a package: a template, the companies that will
 * compete, and a name. A **facilitator** receives it and runs sessions from it, and cannot change
 * what is inside. That is why publishing is here and editing templates lives elsewhere: the same
 * screen would let the person running a class quietly change the economy mid-term.
 *
 * <p>Publishing records the template's version, so a later edit to the template cannot reach back
 * into a package already handed over.
 */
@RestController
@RequestMapping("/api/packages")
public class PackageController {

    public record PublishRequest(String name, String templateKey, List<Long> companyProfileIds) {
    }

    /** What a facilitator sends when the class begins: a name and who to seat. Nothing else. */
    public record StartSessionRequest(String name, List<Long> traineeIds, Integer rounds, Integer cycles) {
    }

    private final PackageRepository packages;
    private final ScenarioRepository templates;
    private final CompanyProfileRepository profiles;
    private final RunService runs;

    public PackageController(PackageRepository packages, ScenarioRepository templates,
                             CompanyProfileRepository profiles, RunService runs) {
        this.packages = packages;
        this.templates = templates;
        this.profiles = profiles;
        this.runs = runs;
    }

    @GetMapping
    public List<PackageRepository.PackageRow> list() {
        return packages.list(requireStaff().tenantId());
    }

    /** Publishing is a builder's act. A facilitator consumes packages; they do not mint them. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PackageRepository.PackageRow publish(@RequestBody PublishRequest request) {
        TenantContext.Principal me = requireBuilder();
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw ProblemException.badRequest("package.name_required", "a package needs a name",
                    "field", "name");
        }
        if (request.templateKey() == null || request.templateKey().isBlank()) {
            throw ProblemException.badRequest("package.template_required", "a package needs a template",
                    "field", "templateKey");
        }
        ScenarioRepository.TemplateRow template = templates.findVisible(me.tenantId(), request.templateKey())
                .orElseThrow(() -> ProblemException.notFound("template.not_found", "no such template"));

        List<Long> profileIds = request.companyProfileIds() == null ? List.of() : request.companyProfileIds();
        if (profileIds.isEmpty()) {
            throw ProblemException.badRequest("package.companies_required",
                    "a package needs at least one company", "field", "companyProfileIds");
        }
        for (Long profileId : profileIds) {
            profiles.findForTenant(me.tenantId(), profileId).orElseThrow(
                    () -> ProblemException.notFound("company_profile.not_found",
                            "no such company profile in this tenant"));
        }

        // Freeze first: the package must point at a row that nothing can edit afterwards. The draft
        // stays a working document; this copy is what the class will actually play.
        String frozenKey = frozenKey(me.tenantCode());
        templates.freeze(template.key(), me.tenantId(), frozenKey,
                request.name().trim() + " (v" + template.version() + ")");
        long id = packages.insert(me.tenantId(), request.name().trim(), frozenKey, template.key(),
                template.version(), profileIds, me.userId());
        templates.markFrozen(frozenKey, id);
        return packages.find(me.tenantId(), id).orElseThrow();
    }

    /** Retiring keeps the history: the sessions it ran still name the package. */
    @PostMapping("/{id}/retire")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retire(@PathVariable long id) {
        TenantContext.Principal me = requireBuilder();
        if (!packages.retire(me.tenantId(), id)) {
            throw ProblemException.notFound("package.not_found", "no such package, or already retired");
        }
    }

    /**
     * Execution begins here: a facilitator picks a published package and seats the class. Everything
     * about the simulation comes from the package; this request cannot change any of it.
     */
    @PostMapping("/{id}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public RunService.RunView startSession(@PathVariable long id,
                                           @RequestBody(required = false) StartSessionRequest request) {
        TenantContext.Principal me = requireFacilitator();
        StartSessionRequest body = request == null
                ? new StartSessionRequest(null, List.of(), null, null) : request;
        return runs.startFromPackage(me.tenantId(), me.userId(), id,
                body.name() == null || body.name().isBlank() ? "Session" : body.name(),
                body.traineeIds(), body.rounds(), body.cycles());
    }

    private static TenantContext.Principal requireFacilitator() {
        TenantContext.Principal me = TenantContext.require();
        if (!"FACILITATOR".equals(me.role()) && !"ADMIN".equals(me.role())) {
            throw ProblemException.forbidden("auth.facilitator_only",
                    "only a facilitator runs a session");
        }
        return me;
    }

    /** `pkg-<tenant>-<8 hex>`: recognisable in logs, never colliding with a builder's own keys. */
    private static String frozenKey(String tenantCode) {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        return ("pkg-" + tenantCode + "-" + suffix).toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }

    private static TenantContext.Principal requireBuilder() {
        TenantContext.Principal me = TenantContext.require();
        if (!"BUILDER".equals(me.role()) && !"ADMIN".equals(me.role())) {
            throw ProblemException.forbidden("auth.builder_only",
                    "only a builder prepares packages; ask your administrator");
        }
        return me;
    }

    private static TenantContext.Principal requireStaff() {
        TenantContext.Principal me = TenantContext.require();
        if (!"BUILDER".equals(me.role()) && !"FACILITATOR".equals(me.role())
                && !"ADMIN".equals(me.role())) {
            throw ProblemException.forbidden("auth.facilitator_only", "staff only");
        }
        return me;
    }
}
