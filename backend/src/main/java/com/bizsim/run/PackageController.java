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

import java.util.ArrayList;
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

    /**
     * {@code traineeIds} is positional and optional: index i is the person who will sit in company i,
     * or null to leave that company open for a walk-in. This is the prepared class list.
     */
    /** One simulation of a programme. Omitted entirely, the package is a single part (D62). */
    public record PartRequest(String name, String templateKey) {
    }

    public record PublishRequest(String name, String templateKey, List<Long> companyProfileIds,
                                 List<Long> traineeIds, String locale, List<PartRequest> parts) {
    }

    /** What a facilitator sends when the class begins: a name and who to seat. Nothing else. */
    public record StartSessionRequest(String name, List<Long> traineeIds, Integer rounds, Integer cycles,
                                      /** Which part of the programme; 1 when omitted (D62). */
                                      Integer part) {
    }

    private final PackageRepository packages;
    private final ScenarioRepository templates;
    private final CompanyProfileRepository profiles;
    private final RunService runs;

    public PackageController(PackageRepository packages, ScenarioRepository templates,
                             CompanyProfileRepository profiles, RunService runs,
                             com.bizsim.user.UserRepository users) {
        this.packages = packages;
        this.templates = templates;
        this.profiles = profiles;
        this.runs = runs;
        this.users = users;
    }

    @GetMapping
    public List<PackageRepository.PackageRow> list() {
        return packages.list(requireStaff().tenantId());
    }

    /** Publishing is a builder's act. A facilitator consumes packages; they do not mint them. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    // One unit of work: the frozen copy, the package row and the frozen mark either all exist or none
    // does. Without this the repository calls commit one by one and a later failure orphans a copy.
    @org.springframework.transaction.annotation.Transactional
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

        List<Long> seats = request.traineeIds() == null ? List.of() : request.traineeIds();
        if (!seats.isEmpty() && seats.size() != profileIds.size()) {
            throw ProblemException.badRequest("package.seats_mismatch",
                    "give one trainee per company, or none", "companies", profileIds.size(),
                    "trainees", seats.size());
        }
        boolean seatsOpponent = false;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Long traineeId : seats) {
            if (traineeId == null) {
                continue;
            }
            if (!seen.add(traineeId)) {
                throw ProblemException.badRequest("package.trainee_twice",
                        "the same person cannot sit in two companies of one package",
                        "userId", traineeId);
            }
            if ("AUTO_WORKER".equals(seatable(me.tenantId(), traineeId).role())) {
                seatsOpponent = true;
            }
        }

        // D48: an opponent is a template mechanic. A package built from a template that never turned it
        // on must not quietly acquire one, so this refuses before the freeze rather than at round close.
        if (seatsOpponent && !templates.load(template.key()).params().autoWorkers()) {
            throw ProblemException.conflict("bot.switch_off",
                    "this template does not offer auto workers; enable the switch on the template and "
                            + "publish again", "template", template.key());
        }

        // The class language travels with the package: a trainee seated from it reads the interface in
        // this language, whatever their own preference says. Blank/null means "no class language".
        // D66: the class language is pinned when the package is published. It decides the interface
        // language *and* which R&D catalogue the class reads, and "follow each trainee" cannot express
        // that — a trainee's own choice must never decide which project set the class competes on.
        String locale = request.locale() == null ? "" : request.locale().trim().toLowerCase();
        if (locale.isBlank()) {
            throw ProblemException.badRequest("package.locale_required",
                    "choose the class language: English or Chinese", "field", "locale");
        }
        if (!java.util.List.of("en", "zh").contains(locale)) {
            throw ProblemException.badRequest("package.locale_invalid",
                    "the class language must be en or zh", "field", "locale");
        }


        // The programme's parts (D62). No parts given means one part built from the package's own
        // template, which is the behaviour every package had before programmes existed.
        List<PartRequest> requestedParts = request.parts() == null || request.parts().isEmpty()
                ? List.of(new PartRequest(request.name().trim(), request.templateKey()))
                : request.parts();
        List<ScenarioRepository.TemplateRow> partTemplates = new ArrayList<>();
        for (int i = 0; i < requestedParts.size(); i++) {
            PartRequest part = requestedParts.get(i);
            if (part == null || part.templateKey() == null || part.templateKey().isBlank()) {
                throw ProblemException.badRequest("package.part_template_required",
                        "part " + (i + 1) + " needs a template", "part", i + 1);
            }
            partTemplates.add(templates.findVisible(me.tenantId(), part.templateKey())
                    .orElseThrow(() -> ProblemException.notFound("template.not_found",
                            "no such template: " + part.templateKey())));
        }

        // Freeze last, once nothing can refuse: a frozen copy is a row in `scenario`, and a publish
        // that failed after freezing used to leave one behind — visible in the template library and
        // attached to no package (D54). The draft stays a working document; this copy is what the
        // class will actually play. One frozen copy per part, because each part may be a different
        // template and the class must never be able to change what it played.
        List<String> frozenKeys = new ArrayList<>();
        for (int i = 0; i < partTemplates.size(); i++) {
            ScenarioRepository.TemplateRow partTemplate = partTemplates.get(i);
            String frozenPartKey = frozenKey(me.tenantCode());
            String partName = requestedParts.get(i).name() == null
                    || requestedParts.get(i).name().isBlank()
                    ? "Part " + (i + 1) : requestedParts.get(i).name().trim();
            templates.freeze(partTemplate.key(), me.tenantId(), frozenPartKey,
                    request.name().trim() + " · " + partName + " (v" + partTemplate.version() + ")");
            frozenKeys.add(frozenPartKey);
        }

        // Part 1's frozen key stays in the package's own columns, so every existing reader — the
        // session start, the reports, the list — keeps working on a single-part package unchanged.
        long id = packages.insert(me.tenantId(), request.name().trim(), frozenKeys.get(0),
                partTemplates.get(0).key(), partTemplates.get(0).version(), profileIds, seats, locale,
                me.userId());
        for (int i = 0; i < frozenKeys.size(); i++) {
            ScenarioRepository.TemplateRow partTemplate = partTemplates.get(i);
            String partName = requestedParts.get(i).name() == null
                    || requestedParts.get(i).name().isBlank()
                    ? "Part " + (i + 1) : requestedParts.get(i).name().trim();
            packages.insertPart(id, i + 1, partName, frozenKeys.get(i), partTemplate.key(),
                    partTemplate.version());
            templates.markFrozen(frozenKeys.get(i), id);
        }
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

    private final com.bizsim.user.UserRepository users;

    /**
     * A seat holds a person or an opponent (D47/D54a). An auto worker is a seatable identity with a
     * brain attached; anything else — staff — is refused, because staff run the class, they do not
     * compete in it.
     */
    private com.bizsim.user.UserRepository.AppUser seatable(long tenantId, long userId) {
        com.bizsim.user.UserRepository.AppUser user = users.findInTenant(tenantId, userId)
                .filter(candidate -> java.util.List.of("PLAYER", "AUTO_WORKER")
                        .contains(candidate.role()) && candidate.active())
                .orElseThrow(() -> ProblemException.badRequest("user.not_found",
                        "no active trainee or auto worker with id " + userId + " in this tenant",
                        "userId", userId));
        return user;
    }

    private void profilesOf(long tenantId, long userId) {
        seatable(tenantId, userId);
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
