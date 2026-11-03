package com.bizsim.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Creates one facilitator account on first boot, driven by environment variables.
 *
 * <p>Why it exists: the demo seeder is a development convenience behind a profile, and a deployment
 * should not depend on remembering to switch a profile. This runs only when all three variables are
 * present, creates the tenant and user if they are missing, and never rewrites an existing account —
 * so leaving the variables set by accident cannot reset anyone's password.
 *
 * <pre>
 *   BIZSIM_BOOTSTRAP_TENANT       tenant code, e.g. demo
 *   BIZSIM_BOOTSTRAP_EMAIL        the owner's email
 *   BIZSIM_BOOTSTRAP_ADMIN_SECRET initial credential, at least 8 characters
 *   BIZSIM_BOOTSTRAP_ROLE         optional, ADMIN by default (ADMIN, FACILITATOR or BUILDER)
 * </pre>
 *
 * <p>Remove the three variables once you have signed in; they are a bootstrap, not configuration.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final int MIN_LENGTH = 8;

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private final String tenantCode;
    private final String email;
    private final String secret;
    /**
     * The first account is an ADMIN by default: it is the owner's account, and only an admin can
     * create the builder and facilitator accounts the two-party model needs (D24).
     */
    private final String role;

    public AdminBootstrap(JdbcTemplate jdbc,
                          @Value("${BIZSIM_BOOTSTRAP_TENANT:}") String tenantCode,
                          @Value("${BIZSIM_BOOTSTRAP_EMAIL:}") String email,
                          @Value("${BIZSIM_BOOTSTRAP_ADMIN_SECRET:}") String secret,
                          @Value("${BIZSIM_BOOTSTRAP_ROLE:ADMIN}") String role) {
        this.jdbc = jdbc;
        this.tenantCode = tenantCode;
        this.email = email;
        this.secret = secret;
        this.role = List.of("ADMIN", "FACILITATOR", "BUILDER").contains(role) ? role : "ADMIN";
    }

    @Override
    public void run(ApplicationArguments args) {
        if (tenantCode.isBlank() || email.isBlank() || secret.isBlank()) {
            return;
        }
        if (secret.length() < MIN_LENGTH) {
            log.warn("bootstrap admin: not created, the initial credential is shorter than {} characters",
                    MIN_LENGTH);
            return;
        }

        Long tenantId = jdbc.query(
                "select id from tenant where code = ?", (rs, i) -> rs.getLong(1), tenantCode)
                .stream().findFirst().orElse(null);

        if (tenantId == null) {
            tenantId = jdbc.queryForObject(
                    "insert into tenant (code, name) values (?, ?) returning id",
                    Long.class, tenantCode, tenantCode);
            log.info("bootstrap admin: created tenant '{}'", tenantCode);
        }

        Integer users = jdbc.queryForObject(
                "select count(*) from app_user where tenant_id = ? and email = ?",
                Integer.class, tenantId, email);
        if (users != null && users > 0) {
            log.info("bootstrap admin: '{}' already exists, left untouched", email);
            return;
        }

        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?)
                """, tenantId, email, encoder.encode(secret), email, role);

        log.info("bootstrap admin: created {} '{}' in tenant '{}'. "
                + "Remove the BIZSIM_BOOTSTRAP_* variables once you have signed in.",
                role, email, tenantCode);
    }
}
