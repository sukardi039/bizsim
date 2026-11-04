package com.bizsim.user;

import com.bizsim.tenant.TenantContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.bizsim.web.ProblemException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Account management inside one tenant.
 *
 * <p>A class needs one account per company. Building a class is the BUILDER's job (ruling 4 Nov
 * 2026): the builder prepares the economy, the companies, the people and the package, and the
 * facilitator does nothing but run the session. So the people screens and the account writes are
 * open to staff, while the class-list read ({@code GET /api/users/trainees}) returns only active
 * trainees — that is all a package needs. The tenant always comes from the token, so an account can
 * never be created in another tenant.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    /** Shortest initial credential a facilitator may hand to a trainee. */
    private static final int MIN_LENGTH = 8;

    public record CreateUserRequest(
            @NotBlank @Email String email,
            String displayName,
            @NotBlank String password,
            String role) {
    }

    /** What the console shows: never the hash, never the password. */
    public record UserView(long id, String email, String displayName, String role, boolean active) {
    }

    private final UserRepository users;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserController(UserRepository users) {
        this.users = users;
    }

    /**
     * The people who can occupy a company in a package or a session. Deliberately narrow: staff
     * accounts are an administrator's business, and the builder only needs the trainees.
     */
    @GetMapping("/trainees")
    public List<UserView> trainees() {
        TenantContext.Principal me = requireStaff();
        return users.findActivePlayers(me.tenantId()).stream()
                .map(u -> new UserView(u.id(), u.email(), u.displayName(), u.role(), u.active()))
                .toList();
    }

    @GetMapping
    public List<UserView> list() {
        TenantContext.Principal me = requireStaff();
        return users.findAllInTenant(me.tenantId()).stream()
                .map(u -> new UserView(u.id(), u.email(), u.displayName(), u.role(), u.active()))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody CreateUserRequest request) {
        TenantContext.Principal me = requireStaff();

        String role = request.role() == null || request.role().isBlank() ? "PLAYER" : request.role();
        // ADMIN is creatable too: the "make another admin before demoting the last one" rule needs a
        // way to make one, and a tenant with a single admin is one forgotten password from stuck.
        if (!List.of("PLAYER", "FACILITATOR", "BUILDER", "ADMIN").contains(role)) {
            throw ProblemException.badRequest("user.role_invalid",
                    "role must be PLAYER, FACILITATOR, BUILDER or ADMIN");
        }
        // D21: a second facilitator is how two cohorts run at once, but that is an admin's call --
        // a facilitator adding another facilitator could quietly take over the next class's account.
        // Preparing is a separate job from running (ruling 3 Nov 2026): a builder account is an
        // admin's call, exactly like a facilitator's -- a facilitator must not mint the person who
        // will rewrite the economy next term, and a builder must not mint the person who will run
        // next term's class either. Trainees are the one role staff may create.
        if (!"PLAYER".equals(role) && !"ADMIN".equals(me.role())) {
            throw ProblemException.forbidden("user.facilitator_needs_admin",
                    "only an admin may create a staff account");
        }
        if (request.password().length() < MIN_LENGTH) {
            throw ProblemException.badRequest("user.password_too_short",
                    "the initial password must be at least " + MIN_LENGTH + " characters",
                    "min", MIN_LENGTH);
        }
        if (users.findByEmailInTenant(me.tenantId(), request.email()).isPresent()) {
            throw ProblemException.conflict("user.duplicate_email",
                    "an account with that email already exists in this tenant");
        }

        String displayName = request.displayName() == null || request.displayName().isBlank()
                ? request.email() : request.displayName().trim();
        UserRepository.AppUser created = users.insert(me.tenantId(),
                request.email().trim().toLowerCase(java.util.Locale.ROOT),
                displayName, encoder.encode(request.password()), role);
        return new UserView(created.id(), created.email(), created.displayName(), created.role(),
                created.active());
    }

    /**
     * Removes a trainee who never played. Once they have submitted a decision the account is history —
     * the decision references it — so the answer is 409 and the tool for "they left" is deactivation.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        TenantContext.Principal me = requireStaff();
        if (me.userId() == id) {
            throw ProblemException.badRequest("user.self_delete", "you cannot delete your own account");
        }
        if (users.hasHistory(me.tenantId(), id)) {
            throw ProblemException.conflict("user.has_played",
                    "this trainee has already played; deactivate the account instead of deleting it");
        }
        if (!users.delete(me.tenantId(), id)) {
            // Either unknown, another tenant's, or not a trainee: one answer, no enumeration.
            throw ProblemException.notFound("user.not_found",
                    "no such trainee in this tenant (only PLAYER accounts can be deleted)");
        }
    }

    /** A trainee who left: the login stops working, the history stays. */
    public record RoleRequest(String role) {
    }

    public record PasswordRequest(String password) {
    }

    /**
     * Changes an account's role. Admin only, and never your own: a mistyped role is the commonest
     * onboarding mistake, and deleting and recreating the account loses its history.
     *
     * <p>The last active admin cannot be demoted — that would leave the tenant with nobody who can
     * create staff accounts.
     */
    /**
     * Sets a new password for any account in the tenant. There was no such thing before, so a
     * mistyped or forgotten credential was a dead end short of SQL — which is how "the account has no
     * proper password" became a support question.
     */
    @PutMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable long id, @RequestBody PasswordRequest request) {
        TenantContext.Principal me = requireStaff();
        if (request == null || request.password() == null || request.password().length() < MIN_LENGTH) {
            throw ProblemException.badRequest("user.password_too_short",
                    "the password must be at least " + MIN_LENGTH + " characters", "min", MIN_LENGTH);
        }
        // Staff may fix a trainee's credential -- a builder hands out the initial one, so losing it
        // is their support question. A staff account's password stays an administrator's business.
        UserRepository.AppUser target = users.findByIdInTenant(id, me.tenantId()).orElseThrow(
                () -> ProblemException.notFound("user.not_found", "no such account in this tenant"));
        if (!"PLAYER".equals(target.role()) && !"ADMIN".equals(me.role())) {
            throw ProblemException.forbidden("user.admin_only",
                    "only an admin may reset a staff password");
        }
        if (!users.setPassword(me.tenantId(), id, encoder.encode(request.password()))) {
            throw ProblemException.notFound("user.not_found", "no such account in this tenant");
        }
    }

    @PatchMapping("/{id}/role")
    public UserView setRole(@PathVariable long id, @RequestBody RoleRequest request) {
        TenantContext.Principal me = requireAdmin();
        String role = request == null || request.role() == null ? "" : request.role().trim().toUpperCase();
        if (!List.of("PLAYER", "FACILITATOR", "BUILDER", "ADMIN").contains(role)) {
            throw ProblemException.badRequest("user.role_invalid",
                    "role must be PLAYER, FACILITATOR, BUILDER or ADMIN", "field", "role");
        }
        if (id == me.userId()) {
            throw ProblemException.badRequest("user.cannot_change_own_role",
                    "ask another admin to change your role");
        }
        UserRepository.AppUser target = users.findByIdInTenant(id, me.tenantId()).orElseThrow(
                () -> ProblemException.notFound("user.not_found", "no such account in this tenant"));
        if ("ADMIN".equals(target.role()) && !"ADMIN".equals(role) && users.countAdmins(me.tenantId()) <= 1) {
            throw ProblemException.conflict("user.last_admin",
                    "this is the only admin left; make another admin first");
        }
        users.setRole(me.tenantId(), id, role);
        UserRepository.AppUser updated = users.findByIdInTenant(id, me.tenantId()).orElseThrow();
        return new UserView(updated.id(), updated.email(), updated.displayName(), updated.role(),
                updated.active());
    }

    @PostMapping("/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable long id) {
        setActive(id, false);
    }

    @PostMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(@PathVariable long id) {
        setActive(id, true);
    }

    private void setActive(long id, boolean active) {
        TenantContext.Principal me = requireStaff();
        if (!users.setActive(me.tenantId(), id, active)) {
            throw ProblemException.notFound("user.not_found", "no such trainee in this tenant");
        }
    }

    private static TenantContext.Principal requireAdmin() {
        TenantContext.Principal me = TenantContext.require();
        if (!"ADMIN".equals(me.role())) {
            throw ProblemException.forbidden("user.admin_only",
                    "only an admin may change a role");
        }
        return me;
    }

    /**
     * Building a class needs the people in it, so every staff role may read and write trainee
     * accounts. Running a session is the facilitator's only job (ruling 4 Nov 2026) -- the console
     * does not show them the people screens at all.
     */
    private static TenantContext.Principal requireStaff() {
        TenantContext.Principal me = TenantContext.require();
        if (!List.of("BUILDER", "FACILITATOR", "ADMIN").contains(me.role())) {
            throw ProblemException.forbidden("auth.staff_only", "staff role required");
        }
        return me;
    }
}
