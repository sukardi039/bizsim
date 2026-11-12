package com.bizsim.run;

import com.bizsim.sim.ScenarioRepository;
import com.bizsim.user.UserRepository;
import com.bizsim.web.ProblemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Publishing a package: the one place where a template is frozen and a class is constituted.
 *
 * <p>Why this lives in a service and not in the controller: publishing is not a screen, it is a set of
 * ordering rules (validate everything first, freeze last, one frozen copy per part, seat list
 * positional). A second caller appeared — the init-data seeder — and duplicating those rules there
 * would have been the classic way for a demo dataset to acquire a shape the console cannot render.
 * The controller now only decides <em>who</em> may publish; this decides <em>what</em> publishing means.
 */
@Service
public class PackageService {

    /**
     * {@code traineeIds} is positional and optional: index i is the person who will sit in company i,
     * or null to leave that company open for a walk-in. This is the prepared class list.
     */
    public record PartRequest(String name, String templateKey) {
    }

    public record PublishRequest(String name, String templateKey, List<Long> companyProfileIds,
                                 List<Long> traineeIds, String locale, List<PartRequest> parts) {
    }

    private final PackageRepository packages;
    private final ScenarioRepository templates;
    private final CompanyProfileRepository profiles;
    private final UserRepository users;

    public PackageService(PackageRepository packages, ScenarioRepository templates,
                          CompanyProfileRepository profiles, UserRepository users) {
        this.packages = packages;
        this.templates = templates;
        this.profiles = profiles;
        this.users = users;
    }

    /**
     * Publishes a package for a tenant. The caller has already established that {@code actorId} may
     * publish (a builder or an admin); everything after that is validation and the write itself.
     */
    // One unit of work: the frozen copy, the package row and the frozen mark either all exist or none
    // does. Without this the repository calls commit one by one and a later failure orphans a copy.
    @Transactional
    public PackageRepository.PackageRow publish(long tenantId, String tenantCode, long actorId,
                                                PublishRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw ProblemException.badRequest("package.name_required", "a package needs a name",
                    "field", "name");
        }
        if (request.templateKey() == null || request.templateKey().isBlank()) {
            throw ProblemException.badRequest("package.template_required", "a package needs a template",
                    "field", "templateKey");
        }
        ScenarioRepository.TemplateRow template = templates.findVisible(tenantId, request.templateKey())
                .orElseThrow(() -> ProblemException.notFound("template.not_found", "no such template"));

        List<Long> profileIds = request.companyProfileIds() == null ? List.of() : request.companyProfileIds();
        if (profileIds.isEmpty()) {
            throw ProblemException.badRequest("package.companies_required",
                    "a package needs at least one company", "field", "companyProfileIds");
        }
        for (Long profileId : profileIds) {
            profiles.findForTenant(tenantId, profileId).orElseThrow(
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
        Set<Long> seen = new HashSet<>();
        for (Long traineeId : seats) {
            if (traineeId == null) {
                continue;
            }
            if (!seen.add(traineeId)) {
                throw ProblemException.badRequest("package.trainee_twice",
                        "the same person cannot sit in two companies of one package",
                        "userId", traineeId);
            }
            if ("AUTO_WORKER".equals(seatable(tenantId, traineeId).role())) {
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
                    "choose the class language", "field", "locale");
        }
        // D86: any two-letter language is a class language — the environment and the R&D catalogue
        // carry a set per language, so a new language is data, not a code change.
        if (!locale.matches("[a-z]{2}")) {
            throw ProblemException.badRequest("package.locale_invalid",
                    "the class language is two lowercase letters, e.g. en, zh, ms", "field", "locale");
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
            partTemplates.add(templates.findVisible(tenantId, part.templateKey())
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
            String frozenPartKey = frozenKey(tenantCode);
            String partName = requestedParts.get(i).name() == null
                    || requestedParts.get(i).name().isBlank()
                    ? "Part " + (i + 1) : requestedParts.get(i).name().trim();
            templates.freeze(partTemplate.key(), tenantId, frozenPartKey,
                    request.name().trim() + " · " + partName + " (v" + partTemplate.version() + ")");
            frozenKeys.add(frozenPartKey);
        }

        // Part 1's frozen key stays in the package's own columns, so every existing reader — the
        // session start, the reports, the list — keeps working on a single-part package unchanged.
        long id = packages.insert(tenantId, request.name().trim(), frozenKeys.get(0),
                partTemplates.get(0).key(), partTemplates.get(0).version(), profileIds, seats, locale,
                actorId);
        for (int i = 0; i < frozenKeys.size(); i++) {
            ScenarioRepository.TemplateRow partTemplate = partTemplates.get(i);
            String partName = requestedParts.get(i).name() == null
                    || requestedParts.get(i).name().isBlank()
                    ? "Part " + (i + 1) : requestedParts.get(i).name().trim();
            packages.insertPart(id, i + 1, partName, frozenKeys.get(i), partTemplate.key(),
                    partTemplate.version());
            templates.markFrozen(frozenKeys.get(i), id);
        }
        return packages.find(tenantId, id).orElseThrow();
    }

    /**
     * A seat holds a person or an opponent (D47/D54a). An auto worker is a seatable identity with a
     * brain attached; anything else — staff — is refused, because staff run the class, they do not
     * compete in it.
     */
    private UserRepository.AppUser seatable(long tenantId, long userId) {
        return users.findInTenant(tenantId, userId)
                .filter(candidate -> List.of("PLAYER", "AUTO_WORKER")
                        .contains(candidate.role()) && candidate.active())
                .orElseThrow(() -> ProblemException.badRequest("user.not_found",
                        "no active trainee or auto worker with id " + userId + " in this tenant",
                        "userId", userId));
    }

    /** `pkg-<tenant>-<8 hex>`: recognisable in logs, never colliding with a builder's own keys. */
    private static String frozenKey(String tenantCode) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return ("pkg-" + tenantCode + "-" + suffix).toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }
}
