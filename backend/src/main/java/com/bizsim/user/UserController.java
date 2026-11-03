package com.bizsim.user;

import com.bizsim.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Account management inside one tenant.
 *
 * <p>A class needs one account per company, and until now the only ways to make one were the dev
 * seeder and the bootstrap — both of which run at startup and make exactly one account. A
 * facilitator can now create the trainee accounts for their own cohort; the tenant always comes from
 * the token, so an account cannot be created in another tenant.
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
    public record UserView(long id, String email, String displayName, String role) {
    }

    private final UserRepository users;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserController(UserRepository users) {
        this.users = users;
    }

    @GetMapping
    public List<UserView> list() {
        TenantContext.Principal me = requireFacilitator();
        return users.findAllInTenant(me.tenantId()).stream()
                .map(u -> new UserView(u.id(), u.email(), u.displayName(), u.role()))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody CreateUserRequest request) {
        TenantContext.Principal me = requireFacilitator();

        String role = request.role() == null || request.role().isBlank() ? "PLAYER" : request.role();
        if (!"PLAYER".equals(role) && !"FACILITATOR".equals(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "role must be PLAYER or FACILITATOR");
        }
        // D21: a second facilitator is how two cohorts run at once, but that is an admin's call --
        // a facilitator adding another facilitator could quietly take over the next class's account.
        if ("FACILITATOR".equals(role) && !"ADMIN".equals(me.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "only an admin may create a facilitator account");
        }
        if (request.password().length() < MIN_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "the initial password must be at least " + MIN_LENGTH + " characters");
        }
        if (users.findByEmailInTenant(me.tenantId(), request.email()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "an account with that email already exists in this tenant");
        }

        String displayName = request.displayName() == null || request.displayName().isBlank()
                ? request.email() : request.displayName().trim();
        UserRepository.AppUser created = users.insert(me.tenantId(), request.email().trim(),
                displayName, encoder.encode(request.password()), role);
        return new UserView(created.id(), created.email(), created.displayName(), created.role());
    }

    private static TenantContext.Principal requireFacilitator() {
        TenantContext.Principal me = TenantContext.require();
        if (!"FACILITATOR".equals(me.role()) && !"ADMIN".equals(me.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "facilitator only");
        }
        return me;
    }
}
