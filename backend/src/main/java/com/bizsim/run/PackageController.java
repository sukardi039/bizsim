package com.bizsim.run;

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
 *
 * <p>The rules of publishing itself live in {@link PackageService}: a second caller (the init-data
 * seeder) needs them, and two copies of an ordering rule is one copy too many.
 */
@RestController
@RequestMapping("/api/packages")
public class PackageController {

    /** What a facilitator sends when the class begins: a name and who to seat. Nothing else. */
    public record StartSessionRequest(String name, List<Long> traineeIds, Integer rounds, Integer cycles,
                                      /** Which part of the programme; 1 when omitted (D62). */
                                      Integer part) {
    }

    private final PackageRepository packages;
    private final PackageService packageService;
    private final RunService runs;

    public PackageController(PackageRepository packages, PackageService packageService,
                             RunService runs) {
        this.packages = packages;
        this.packageService = packageService;
        this.runs = runs;
    }

    @GetMapping
    public List<PackageRepository.PackageRow> list() {
        return packages.list(requireStaff().tenantId());
    }

    /** Publishing is a builder's act. A facilitator consumes packages; they do not mint them. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PackageRepository.PackageRow publish(@RequestBody PackageService.PublishRequest request) {
        TenantContext.Principal me = requireBuilder();
        return packageService.publish(me.tenantId(), me.tenantCode(), me.userId(), request);
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
                ? new StartSessionRequest(null, List.of(), null, null, null) : request;
        return runs.startFromPackage(me.tenantId(), me.userId(), id,
                body.name() == null || body.name().isBlank() ? "Session" : body.name(),
                body.traineeIds(), body.rounds(), body.cycles(), body.part());
    }

    /**
     * D62: the programme's parts as a comparison — per company across parts, with the move between
     * parts and against the class's own move. This is what a facilitator projects between parts.
     */
    @GetMapping("/{id}/comparison")
    public java.util.Map<String, Object> comparison(@PathVariable long id) {
        TenantContext.Principal me = requireStaff();
        return runs.comparison(me.tenantId(), id);
    }

    /**
     * D62: reset the whole programme — every part back to month 0. Resetting one part is the run
     * screen's Reset; this is "the course did not work, start it again".
     */
    @PostMapping("/{id}/reset")
    public java.util.Map<String, Object> resetProgramme(@PathVariable long id,
                                                        @RequestBody(required = false)
                                                        java.util.Map<String, Object> body) {
        TenantContext.Principal me = requireFacilitator();
        String reason = body == null || body.get("reason") == null ? null
                : String.valueOf(body.get("reason"));
        return java.util.Map.of("partsReset",
                runs.resetProgramme(me.tenantId(), me.userId(), id, reason));
    }

    /**
     * Tidy up: what a builder may remove — retired packages that never ran a session. Preview first,
     * because deleting a template copy is irreversible.
     */
    @GetMapping("/tidy-up")
    public List<PackageRepository.TidyRow> tidyPreview() {
        return packages.collectable(requireBuilder().tenantId());
    }

    public record TidyResult(int removed, List<String> names) {
    }

    @PostMapping("/tidy-up")
    public TidyResult tidy() {
        TenantContext.Principal me = requireBuilder();
        List<PackageRepository.TidyRow> doomed = packages.collectable(me.tenantId());
        List<String> names = new java.util.ArrayList<>();
        for (PackageRepository.TidyRow row : doomed) {
            names.add(packages.collect(me.tenantId(), row.id()).name());
        }
        return new TidyResult(names.size(), names);
    }

    private static TenantContext.Principal requireFacilitator() {
        TenantContext.Principal me = TenantContext.require();
        if (!"FACILITATOR".equals(me.role()) && !"ADMIN".equals(me.role())) {
            throw ProblemException.forbidden("auth.facilitator_only",
                    "only a facilitator runs a session");
        }
        return me;
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
