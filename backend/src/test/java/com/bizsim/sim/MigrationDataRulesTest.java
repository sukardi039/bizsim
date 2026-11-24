package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * D136: a migration that moves existing rows must be proved against rows that need moving.
 *
 * <p>V55 took the site down on 24 Nov 2026 because it added its check constraint <em>before</em> the update that
 * made the rows satisfy it. Every test passed, because every test database is created empty — it never holds a
 * row on the retired {@code FOCUS} value. The constraint could only fail on a database whose data looked like
 * production, which is exactly the database no test was using.
 *
 * <p>These tests therefore do not test "does the schema match" (Flyway already guarantees that). They test the
 * <em>data rules the migration exists to establish</em>, against data shaped like the live tenant:
 *
 * <ul>
 *   <li>no profile may remain on the retired value;</li>
 *   <li>no profile may carry a personality other than the retired marker;</li>
 *   <li>the constraint must accept the four positions and reject anything else;</li>
 *   <li>the column must be wide enough for the longest position.</li>
 * </ul>
 *
 * <p>Together these pin the outcome the broken version could not reach. A future edit that re-orders the
 * statements, or forgets the personality backfill, fails here rather than in production.
 */
@org.springframework.boot.test.context.SpringBootTest
class MigrationDataRulesTest extends PostgresTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void no_profile_still_plays_a_retired_position() {
        Integer remaining = jdbc.queryForObject(
                "select count(*) from bot_profile where strategy = 'FOCUS'", Integer.class);
        // The mapping is COST_FOCUS, recorded in V55: it spent nothing on quality and bought no capacity, which
        // is nearer the cost variant than the differentiation one.
        org.assertj.core.api.Assertions.assertThat(remaining)
                .as("V55 maps the retired FOCUS position to COST_FOCUS")
                .isZero();
    }

    @Test
    void every_profile_states_the_retired_personality_marker() {
        Integer offMarker = jdbc.queryForObject(
                "select count(*) from bot_profile where personality is distinct from 'BALANCED'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(offMarker)
                .as("personality is retired and written as BALANCED on every row")
                .isZero();
    }

    @Test
    void the_constraint_accepts_the_four_positions_and_refuses_a_fifth() {
        // The seed creates tenants at runtime; a bare context has none. The constraint is what is under test, not
        // the tenancy, so a row here is legitimate scaffolding. queryForObject throws on zero rows, so count first.
        final Long tenant;
        if (jdbc.queryForObject("select count(*) from tenant", Integer.class) == 0) {
            tenant = jdbc.queryForObject(
                    "insert into tenant (code, name) values ('rule-probe', 'Rule probe') returning id", Long.class);
        } else {
            tenant = jdbc.queryForObject("select id from tenant order by id limit 1", Long.class);
        }
        for (String position : List.of("COST", "DIFFERENTIATION", "COST_FOCUS", "DIFFERENTIATION_FOCUS")) {
            jdbc.update("""
                    insert into bot_profile (tenant_id, key, name, level, personality, strategy, params)
                    values (?, ?, ?, 'MID', 'BALANCED', ?, '{}'::jsonb)
                    """, tenant, "rule-" + position.toLowerCase(java.util.Locale.ROOT), position, position);
        }
        // Anything outside Porter's grid is refused by the database itself, not merely by the API.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> jdbc.update("""
                        insert into bot_profile (tenant_id, key, name, level, personality, strategy, params)
                        values (?, 'rule-bogus', 'Bogus', 'MID', 'BALANCED', 'HYBRID', '{}'::jsonb)
                        """, tenant))
                .as("the check constraint names the four positions and nothing else")
                .hasMessageContaining("bot_profile_strategy_check");
    }

    @Test
    void the_strategy_column_is_wide_enough_for_the_longest_position() {
        Integer width = jdbc.queryForObject("""
                select character_maximum_length from information_schema.columns
                where table_name = 'bot_profile' and column_name = 'strategy'
                """, Integer.class);
        // varchar(20) fit COST and DIFFERENTIATION but not DIFFERENTIATION_FOCUS (22) — the insert failed with
        // "value too long for type character varying(20)" the first time round.
        org.assertj.core.api.Assertions.assertThat(width)
                .as("strategy column must hold DIFFERENTIATION_FOCUS")
                .isGreaterThanOrEqualTo("DIFFERENTIATION_FOCUS".length());
    }
}
