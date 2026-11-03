package com.bizsim.web;

import com.bizsim.tenant.TenantContext;
import com.bizsim.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Identity probe. Its second job is to be the cheapest possible proof that tenant isolation works:
 * the tenant in the response comes from the token, not from the request.
 *
 * <p>It also carries the language: the person's choice, else the tenant default (V10). The server
 * never returns translated text — only this code — so a new language is a catalog file on the client.
 */
@RestController
@RequestMapping("/api")
public class MeController {

    /** The languages the product ships. Adding one is this list plus a catalog file. */
    private static final List<String> SUPPORTED = List.of("en", "zh");

    public record Me(long userId, long tenantId, String tenantCode, String role, String locale) {
    }

    public record LocaleRequest(String locale) {
    }

    public record PasswordChange(String currentPassword, String newPassword) {
    }

    private static final int MIN_LENGTH = 8;

    private final UserRepository users;
    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder =
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();

    public MeController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/ping")
    public String ping() {
        return "ok";
    }

    /**
     * A person changes their own password. Requires the current one: a stolen session must not be
     * enough to lock the owner out of their own account.
     */
    @PostMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@RequestBody PasswordChange request) {
        var me = TenantContext.require();
        UserRepository.AppUser user = users.findByIdInTenant(me.userId(), me.tenantId()).orElseThrow(
                () -> ProblemException.notFound("user.not_found", "no such account"));
        if (request == null || request.currentPassword() == null
                || !encoder.matches(request.currentPassword(), user.passwordHash())) {
            throw ProblemException.badRequest("user.current_password_wrong",
                    "the current password is not correct", "field", "currentPassword");
        }
        if (request.newPassword() == null || request.newPassword().length() < MIN_LENGTH) {
            throw ProblemException.badRequest("user.password_too_short",
                    "the new password must be at least " + MIN_LENGTH + " characters",
                    "min", MIN_LENGTH);
        }
        users.setPassword(me.tenantId(), me.userId(), encoder.encode(request.newPassword()));
    }

    @GetMapping("/me")
    public Me me() {
        TenantContext.Principal p = TenantContext.require();
        return view(p);
    }

    /** Changes the caller's own language. Never accepts a user id — you can only re-language yourself. */
    @PutMapping("/me/locale")
    public Me setLocale(@RequestBody LocaleRequest request) {
        TenantContext.Principal p = TenantContext.require();
        String locale = request == null || request.locale() == null ? "" : request.locale().trim();
        if (!SUPPORTED.contains(locale)) {
            throw ProblemException.badRequest("locale.unsupported",
                    "unsupported locale; expected one of " + SUPPORTED);
        }
        users.setLocale(p.tenantId(), p.userId(), locale);
        return view(p);
    }

    private Me view(TenantContext.Principal p) {
        return new Me(p.userId(), p.tenantId(), p.tenantCode(), p.role(),
                users.localeOf(p.tenantId(), p.userId()));
    }
}
