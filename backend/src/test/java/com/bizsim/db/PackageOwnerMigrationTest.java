package com.bizsim.db;

import com.bizsim.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * D140: V56 adds an owner to packages that already exist, so it must be proved against a database that has some.
 *
 * <p>V55 took the production site down by adding a constraint before the data satisfied it, and no test caught it
 * because every test database is built empty. This is the check that would have caught it, applied to V56: the
 * column is added nullable, filled, and only then made {@code not null}, so the interesting question is not
 * "does the schema match" but "did every existing package end up owned".
 */
@SpringBootTest
class PackageOwnerMigrationTest extends PostgresTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void every_package_has_a_facilitator_after_the_migration() {
        Integer unowned = jdbc.queryForObject(
                "select count(*) from simulation_package where facilitator_id is null", Integer.class);
        org.assertj.core.api.Assertions.assertThat(unowned)
                .as("V56 fills every existing package before making the column not null")
                .isZero();
    }

    @Test
    void the_column_is_not_null_and_points_at_a_real_account() {
        String nullable = jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = 'simulation_package' and column_name = 'facilitator_id'
                """, String.class);
        org.assertj.core.api.Assertions.assertThat(nullable)
                .as("a package must always have an owner").isEqualTo("NO");

        // Every owner resolves to an account in the same tenant as the package — a cross-tenant assignment would
        // make the package invisible to everyone in its own tenant, which is worse than having no rule at all.
        List<String> mismatched = jdbc.queryForList("""
                select p.id || ':' || p.tenant_id || '/' || u.tenant_id
                from simulation_package p join app_user u on u.id = p.facilitator_id
                where u.tenant_id <> p.tenant_id
                """, String.class);
        org.assertj.core.api.Assertions.assertThat(mismatched)
                .as("every package owner belongs to the package's tenant").isEmpty();
    }

    @Test
    void a_package_cannot_be_inserted_without_an_owner() {
        Long tenant = jdbc.queryForObject(
                "insert into tenant (code, name) values ('owner-probe', 'Owner probe') returning id", Long.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                insert into simulation_package (tenant_id, name, template_key, template_version, locale)
                values (?, 'Unowned', 'standard-v1', 1, 'en')
                """, tenant))
                .as("the database refuses an ownerless package, not only the API")
                .hasMessageContaining("facilitator_id");
    }
}
