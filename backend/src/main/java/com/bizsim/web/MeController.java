package com.bizsim.web;

import com.bizsim.tenant.TenantContext;
import com.bizsim.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    private final UserRepository users;

    public MeController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/ping")
    public String ping() {
        return "ok";
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
