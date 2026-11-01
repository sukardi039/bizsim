package com.bizsim.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Demo tenant and accounts for local work, only under the {@code dev} profile.
 *
 * <p>Idempotent: it does nothing once the tenant exists, so restarting the app neither resets a
 * password nor duplicates users. Never active in production — the profile is the guard, not a flag.
 */
@Component
@Profile("dev")
public class DevSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSeeder.class);
    private static final String TENANT_CODE = "demo";
    private static final String DEMO_PASSWORD = "demo1234";

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public DevSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        Integer existing = jdbc.queryForObject(
                "select count(*) from tenant where code = ?", Integer.class, TENANT_CODE);
        if (existing != null && existing > 0) {
            log.info("dev seed: tenant '{}' already present, nothing to do", TENANT_CODE);
            return;
        }

        Long tenantId = jdbc.queryForObject(
                "insert into tenant (code, name) values (?, ?) returning id",
                Long.class, TENANT_CODE, "Demo Company");

        seedUser(tenantId, "admin@demo.test", "Admin", "ADMIN");
        seedUser(tenantId, "trainer@demo.test", "Trainer", "FACILITATOR");
        seedUser(tenantId, "player@demo.test", "Player One", "PLAYER");

        log.info("dev seed: tenant '{}' with 3 users, password '{}'", TENANT_CODE, DEMO_PASSWORD);
    }

    private void seedUser(long tenantId, String email, String name, String role) {
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?)
                """, tenantId, email, encoder.encode(DEMO_PASSWORD), name, role);
    }
}
