package com.bizsim.init;

import com.bizsim.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * D137: the library is filled for the positions that are missing, not only for the accounts that are new.
 *
 * <p>After V55 retired the old nine profiles, live came up with **6 of 12** auto players. The seeder skipped every
 * position whose bot *account* already existed — and those accounts did exist, from the previous library — so the
 * retired rows were never reactivated. The two brand-new positions were created and the ten old ones stayed
 * inactive.
 *
 * <p>The rule: the seeder is driven by whether a position has an **active profile**, and reactivates a retired row
 * on the same key rather than inserting a duplicate (the key is unique per tenant).
 */
@SpringBootTest
class BotLibraryFillTest extends PostgresTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InitDataService seeder;

    @Test
    void every_position_is_active_after_the_seeder_runs_against_a_retired_library() {
        long tenant = jdbc.queryForObject("""
                insert into tenant (code, name) values ('fill-probe', 'Fill probe') returning id
                """, Long.class);

        // Start from the state V55 leaves: some positions retired, their bot accounts still present.
        jdbc.update("""
                insert into bot_profile (tenant_id, key, name, level, personality, strategy, params, active)
                values (?, 'cost-high', 'Cost leader (high)', 'HIGH', 'BALANCED', 'COST', '{}'::jsonb, false)
                """, tenant);
        jdbc.update("""
                insert into app_user (tenant_id, email, display_name, role, password_hash, bot_profile_id)
                select ?, 'cost-high@bots.invalid', 'Cost leader (high)', 'AUTO_WORKER', 'x', id
                from bot_profile where tenant_id = ? and key = 'cost-high'
                """, tenant, tenant);

        seeder.ensureBotLibrary(tenant, "fill-probe");

        Integer active = jdbc.queryForObject(
                "select count(*) from bot_profile where tenant_id = ? and active", Integer.class, tenant);
        org.assertj.core.api.Assertions.assertThat(active)
                .as("twelve positions, four strategies at three levels")
                .isEqualTo(12);
        Integer costHigh = jdbc.queryForObject(
                "select count(*) from bot_profile where tenant_id = ? and key = 'cost-high'", Integer.class, tenant);
        org.assertj.core.api.Assertions.assertThat(costHigh)
                .as("the retired row was reactivated, not duplicated — the key is unique per tenant")
                .isEqualTo(1);
        Integer accounts = jdbc.queryForObject(
                "select count(*) from app_user where tenant_id = ? and role = 'AUTO_WORKER'", Integer.class, tenant);
        org.assertj.core.api.Assertions.assertThat(accounts)
                .as("one bot account per position, reusing the existing one")
                .isEqualTo(12);
    }
}
