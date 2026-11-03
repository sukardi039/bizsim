package com.bizsim.run;

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

import java.util.List;

/**
 * Company profiles (S6e): the reusable definition of a company.
 *
 * <p>Ruling: one global profile that any tenant may use, extended by copying; a profile is reused
 * across simulations. Everything here is facilitator material — a trainee has no business listing
 * their rivals' profiles.
 */
@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    public record ProfileRequest(String name, String companyName, List<String> products, String notes) {
    }

    private final CompanyProfileRepository profiles;

    public CompanyController(CompanyProfileRepository profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public List<CompanyProfileRepository.ProfileRow> list() {
        return profiles.listVisible(requireStaff().tenantId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyProfileRepository.ProfileRow create(@RequestBody ProfileRequest request) {
        TenantContext.Principal me = requireBuilder();
        validate(request);
        long id = profiles.insert(me.tenantId(), request.name().trim(),
                request.companyName() == null ? "" : request.companyName().trim(),
                request.products(), request.notes(), null);
        return profiles.findVisible(me.tenantId(), id).orElseThrow();
    }

    /** Copies a visible profile — the global one included — into a tenant-owned profile. */
    @PostMapping("/{id}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyProfileRepository.ProfileRow copy(@PathVariable long id) {
        return profiles.copy(requireBuilder().tenantId(), id);
    }

    @PutMapping("/{id}")
    public CompanyProfileRepository.ProfileRow update(@PathVariable long id,
                                                      @RequestBody ProfileRequest request) {
        TenantContext.Principal me = requireBuilder();
        validate(request);
        profiles.findVisible(me.tenantId(), id)
                .filter(profile -> profile.editable(me.tenantId()))
                .orElseThrow(() -> ProblemException.conflict("company_profile.read_only",
                        "the shipped profile is read-only; copy it first"));
        profiles.update(me.tenantId(), id, request.name().trim(),
                request.companyName() == null ? "" : request.companyName().trim(),
                request.products(), request.notes());
        return profiles.findVisible(me.tenantId(), id).orElseThrow();
    }

    private static void validate(ProfileRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw ProblemException.badRequest("company_profile.name_required",
                    "a company profile needs a name", "field", "name");
        }
        if (request.name().trim().length() > 120) {
            throw ProblemException.badRequest("company_profile.name_too_long",
                    "a profile name must be 120 characters or fewer", "field", "name", "max", 120);
        }
    }

    /** Building is a separate job from running: companies are prepared, not improvised. */
    private static TenantContext.Principal requireBuilder() {
        TenantContext.Principal me = TenantContext.require();
        if (!"BUILDER".equals(me.role()) && !"ADMIN".equals(me.role())) {
            throw ProblemException.forbidden("auth.builder_only",
                    "only a builder prepares companies; ask your administrator");
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
