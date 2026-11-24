package com.bizsim.init;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * D130: every tenant gets the nine strategy × level auto-player profiles, on every startup.
 *
 * <p>The library was seeded only inside {@link InitDataService}, which a tenant enters once, when it is created.
 * A tenant that already existed never ran it again — and V52 retired that tenant's old personality-era profiles,
 * so the live class was left with an empty library: no opponents to seat, and nothing to show in the builder.
 *
 * <p>This runner closes that: for each tenant, an idempotent call that creates only the profiles that are
 * missing. It is deliberately not a migration — tenants are created by application code after migrations run, so
 * SQL cannot see them, and a migration that joined {@code tenant} silently inserted zero rows.
 *
 * <p>It never touches a profile a trainer has created or edited: the keys are the generated ones, and a profile
 * whose position is already active is left exactly as it is.
 */
@Component
public class BotLibraryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BotLibraryRunner.class);

    private final JdbcTemplate jdbc;
    private final InitDataService init;

    public BotLibraryRunner(JdbcTemplate jdbc, InitDataService init) {
        this.jdbc = jdbc;
        this.init = init;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> tenants = jdbc.queryForList("select id, code from tenant order by id");
        for (Map<String, Object> tenant : tenants) {
            long id = ((Number) tenant.get("id")).longValue();
            String code = String.valueOf(tenant.get("code"));
            // D138: "some profiles are active" is not "the library is complete". After V55 retired the old nine,
            // live held six active profiles — the two new positions — and this check saw active > 0 and skipped,
            // leaving the class with half a library. The library is complete when every position at every level
            // is active, and the expected count comes from limits.json rather than a number written here.
            Long active = jdbc.queryForObject(
                    "select count(*) from bot_profile where tenant_id = ? and active", Long.class, id);
            int expected = init.expectedLibrarySize();
            if (active != null && active >= expected) {
                continue;   // complete; nothing to add, nothing to overwrite
            }
            try {
                init.ensureBotLibrary(id, code);
                log.info("bot library: filled tenant '{}' to {} positions ({} were active before)",
                        code, expected, active);
            } catch (RuntimeException e) {
                // Startup must not fail because a library could not be seeded; the builder still works by hand.
                log.warn("bot library: could not seed tenant '{}': {}", code, e.getMessage());
            }
        }
    }
}
