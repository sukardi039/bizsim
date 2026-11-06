package com.bizsim.db;

import com.bizsim.support.PostgresTestBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the "start the backend and it builds its own schema" promise: the context here boots against
 * an empty PostgreSQL, and every table must exist because Flyway ran during startup.
 */
@SpringBootTest
class SchemaCreationTest extends PostgresTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    /**
     * Guards the settings themselves: a resource file change could quietly re-enable clean or
     * out-of-order, and both are the kind of mistake that only shows up in production.
     */
    @Test
    void flyway_is_configured_the_safe_way() {
        assertThat(flyway).isNotNull();
        assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
        assertThat(flyway.getConfiguration().isValidateOnMigrate()).isTrue();
        assertThat(flyway.getConfiguration().isOutOfOrder()).isFalse();
        assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
    }

    @Test
    void flyway_creates_the_whole_schema_on_a_fresh_database() {
        List<String> tables = jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'public' and table_type = 'BASE TABLE'
                """, String.class);

        assertThat(tables).contains(
                "tenant", "app_user", "simulation", "run", "team", "player", "round", "decision",
                "round_result", "scenario", "product", "channel", "rnd_project", "company_product",
                "company_staff", "company_rnd", "round_statement");

        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(4);

        Integer failed = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where not success", Integer.class);
        assertThat(failed).isZero();
    }

    @Test
    void the_shipped_scenario_is_present_after_startup() {
        Integer products = jdbc.queryForObject(
                "select count(*) from product where scenario_key = 'standard-v1'", Integer.class);
        Integer projects = jdbc.queryForObject(
                "select count(*) from rnd_project where scenario_key = 'standard-v1'", Integer.class);

        assertThat(products).isEqualTo(3);
        // D64: two catalogues, five projects each — a class reads one of them, never both.
        assertThat(projects).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from rnd_project where scenario_key = "
                + "'standard-v1' and lang = 'en'", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from rnd_project where scenario_key = "
                + "'standard-v1' and lang = 'zh'", Integer.class)).isEqualTo(5);
    }
}
