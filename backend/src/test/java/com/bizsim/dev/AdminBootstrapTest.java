package com.bizsim.dev;

import com.bizsim.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/** The deployment's only door to a first account: it must create once, and never overwrite. */
@SpringBootTest
class AdminBootstrapTest extends PostgresTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void creates_the_tenant_and_an_admin_once() {
        String code = "boot" + System.nanoTime();
        String email = "boss@boot.test";

        new AdminBootstrap(jdbc, code, email, "first-credential", "ADMIN", false).run(null);

        Long tenantId = jdbc.queryForObject(
                "select id from tenant where code = ?", Long.class, code);
        assertThat(tenantId).isNotNull();
        assertThat(jdbc.queryForObject(
                "select count(*) from app_user where tenant_id = ? and role = 'ADMIN'",
                Integer.class, tenantId)).isEqualTo(1);

        String firstHash = jdbc.queryForObject(
                "select password_hash from app_user where tenant_id = ? and email = ?",
                String.class, tenantId, email);

        // A second boot with a different credential must not reset the stored one.
        new AdminBootstrap(jdbc, code, email, "second-credential", "ADMIN", false).run(null);

        String secondHash = jdbc.queryForObject(
                "select password_hash from app_user where tenant_id = ? and email = ?",
                String.class, tenantId, email);
        assertThat(secondHash).isEqualTo(firstHash);
        assertThat(jdbc.queryForObject(
                "select count(*) from app_user where tenant_id = ?", Integer.class, tenantId)).isEqualTo(1);
        assertThat(new BCryptPasswordEncoder().matches("first-credential", secondHash)).isTrue();
    }

    @Test
    void promote_changes_the_role_of_an_existing_account_and_nothing_else() {
        String code = "prom" + System.nanoTime();
        new AdminBootstrap(jdbc, code, "boss@prom.test", "first-credential", "FACILITATOR", false)
                .run(null);
        Long tenantId = jdbc.queryForObject("select id from tenant where code = ?", Long.class, code);
        String hashBefore = jdbc.queryForObject(
                "select password_hash from app_user where tenant_id = ?", String.class, tenantId);

        // Without the flag, an existing account is left exactly as it is.
        new AdminBootstrap(jdbc, code, "boss@prom.test", "other-credential", "ADMIN", false).run(null);
        assertThat(jdbc.queryForObject(
                "select role from app_user where tenant_id = ?", String.class, tenantId))
                .isEqualTo("FACILITATOR");

        // With it, the role moves and the credential does not.
        new AdminBootstrap(jdbc, code, "boss@prom.test", "other-credential", "ADMIN", true).run(null);
        assertThat(jdbc.queryForObject(
                "select role from app_user where tenant_id = ?", String.class, tenantId))
                .isEqualTo("ADMIN");
        assertThat(jdbc.queryForObject(
                "select password_hash from app_user where tenant_id = ?", String.class, tenantId))
                .isEqualTo(hashBefore);
    }

    @Test
    void does_nothing_when_the_variables_are_absent() {
        long before = jdbc.queryForObject("select count(*) from tenant", Long.class);

        new AdminBootstrap(jdbc, "", "", "", "ADMIN", false).run(null);
        new AdminBootstrap(jdbc, "tenant-only", "", "", "ADMIN", false).run(null);

        assertThat(jdbc.queryForObject("select count(*) from tenant", Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "select count(*) from tenant where code = 'tenant-only'", Long.class)).isZero();
    }

    @Test
    void refuses_a_credential_that_is_too_short() {
        String code = "short" + System.nanoTime();

        new AdminBootstrap(jdbc, code, "boss@short.test", "tiny", "ADMIN", false).run(null);

        assertThat(jdbc.queryForObject(
                "select count(*) from tenant where code = ?", Long.class, code)).isZero();
    }
}
