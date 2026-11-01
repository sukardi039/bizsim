package com.bizsim.auth;

import com.bizsim.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(
            @NotBlank String tenantCode,
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    public record LoginResponse(String token, long expiresInSeconds, String tenantCode, String role) {
    }

    private final UserRepository users;
    private final JwtService jwt;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthController(UserRepository users, JwtService jwt) {
        this.users = users;
        this.jwt = jwt;
    }

    /**
     * One failure answer for every bad login. Unknown tenant, unknown email, wrong password and a
     * deactivated account all return the same 401 body — otherwise the endpoint enumerates tenants
     * and accounts.
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        UserRepository.AppUser user = users
                .findByTenantCodeAndEmail(request.tenantCode(), request.email())
                .filter(u -> encoder.matches(request.password(), u.passwordHash()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "invalid credentials"));

        String token = jwt.issue(user.id(), user.tenantId(), request.tenantCode(), user.role());
        return new LoginResponse(token, jwt.ttl().toSeconds(), request.tenantCode(), user.role());
    }
}
