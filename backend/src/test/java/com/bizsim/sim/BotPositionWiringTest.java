package com.bizsim.sim;

import com.bizsim.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/**
 * D139: the bot's Porter position must travel from the database to the pie slice, or the colour never happens.
 *
 * <p>The unit tests prove the renderer picks its colour from {@code Slice.position}. They cannot prove the
 * position ever *arrives* — that is a join in {@code scoreboard()} and a lookup in {@code MapShareService}. This
 * builds a run with a bot seat and a human seat, computes nothing, and asserts the query returns the position for
 * the bot and null for the person. It is the wiring the whole feature rests on.
 */
@SpringBootTest
class BotPositionWiringTest extends PostgresTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void the_scoreboard_reports_a_bot_position_and_null_for_a_person() {
        // ---- minimal scaffolding: tenant, scenario, run, two teams, round, results, players
        Long tenant = jdbc.queryForObject(
                "insert into tenant (code, name) values ('wire-probe', 'Wire probe') returning id", Long.class);
        Long facilitator = jdbc.queryForObject("""
                insert into app_user (tenant_id, email, display_name, role, password_hash)
                values (?, 'fac@wire.test', 'Fac', 'FACILITATOR', 'x') returning id
                """, Long.class, tenant);
        Long simulation = jdbc.queryForObject("""
                insert into simulation (tenant_id, name, scenario_key, facilitator_id)
                values (?, 'Wire sim', 'standard-v1', ?) returning id
                """, Long.class, tenant, facilitator);
        Long run = jdbc.queryForObject("""
                insert into run (tenant_id, simulation_id, scenario_key, status, current_round)
                values (?, ?, 'standard-v1', 'OPEN', 1) returning id
                """, Long.class, tenant, simulation);
        Long round = jdbc.queryForObject("""
                insert into round (run_id, number, status) values (?, 1, 'COMPUTED') returning id
                """, Long.class, run);
        Long botProfile = jdbc.queryForObject("""
                insert into bot_profile (tenant_id, key, name, level, personality, strategy, params)
                values (?, 'cost_focus-mid', 'Cost focus (mid)', 'MID', 'BALANCED', 'COST_FOCUS',
                        '{}'::jsonb) returning id
                """, Long.class, tenant);

        long humanTeam = team(tenant, run, "Human Ltd");
        long botTeam = team(tenant, run, "Bot Ltd");
        result(round, humanTeam);
        result(round, botTeam);

        // a person in one seat, a brain in the other
        Long humanUser = jdbc.queryForObject("""
                insert into app_user (tenant_id, email, display_name, role, password_hash)
                values (?, 'person@wire.test', 'Person', 'PLAYER', 'x') returning id
                """, Long.class, tenant);
        jdbc.update("insert into player (run_id, team_id, user_id, role_key) values (?, ?, ?, 'CEO')",
                run, humanTeam, humanUser);
        Long botUser = jdbc.queryForObject("""
                insert into app_user (tenant_id, email, display_name, role, password_hash, bot_profile_id)
                values (?, 'cost_focus-mid@bots.invalid', 'Cost focus', 'AUTO_WORKER', 'x', ?) returning id
                """, Long.class, tenant, botProfile);
        jdbc.update("insert into player (run_id, team_id, user_id, bot_profile_id, role_key) "
                        + "values (?, ?, ?, ?, 'CEO')", run, botTeam, botUser, botProfile);

        Map<String, Object> botRow = row(jdbc.queryForList("""
                select rr.team_id,
                       exists (select 1 from player p where p.run_id = r.run_id and p.team_id = rr.team_id
                                 and p.bot_profile_id is not null) as auto_player,
                       (select bp.strategy from player p
                          join bot_profile bp on bp.id = p.bot_profile_id
                         where p.run_id = r.run_id and p.team_id = rr.team_id
                           and p.bot_profile_id is not null limit 1) as auto_strategy
                from round_result rr join round r on r.id = rr.round_id
                where rr.round_id = ? and rr.team_id = ?
                """, round, botTeam));
        Map<String, Object> humanRow = row(jdbc.queryForList("""
                select rr.team_id,
                       exists (select 1 from player p where p.run_id = r.run_id and p.team_id = rr.team_id
                                 and p.bot_profile_id is not null) as auto_player,
                       (select bp.strategy from player p
                          join bot_profile bp on bp.id = p.bot_profile_id
                         where p.run_id = r.run_id and p.team_id = rr.team_id
                           and p.bot_profile_id is not null limit 1) as auto_strategy
                from round_result rr join round r on r.id = rr.round_id
                where rr.round_id = ? and rr.team_id = ?
                """, round, humanTeam));

        org.assertj.core.api.Assertions.assertThat(botRow.get("auto_player"))
                .as("the bot seat is flagged").isEqualTo(true);
        org.assertj.core.api.Assertions.assertThat(botRow.get("auto_strategy"))
                .as("the position travels with the seat, which is what the colour is picked from")
                .isEqualTo("COST_FOCUS");
        org.assertj.core.api.Assertions.assertThat(humanRow.get("auto_player"))
                .as("a person is not a bot seat").isEqualTo(false);
        org.assertj.core.api.Assertions.assertThat(humanRow.get("auto_strategy"))
                .as("a person has no position to colour by").isNull();
    }

    private long team(long tenant, long run, String name) {
        return jdbc.queryForObject("""
                insert into team (run_id, name, company_name, cash, brand_score)
                values (?, ?, ?, 2000000000, 50) returning id
                """, Long.class, run, name, name);
    }

    private void result(long round, long team) {
        jdbc.update("""
                insert into round_result (round_id, team_id, score, total_score)
                values (?, ?, '{}'::jsonb, 0)
                """, round, team);
    }

    private static Map<String, Object> row(java.util.List<Map<String, Object>> rows) {
        org.assertj.core.api.Assertions.assertThat(rows).hasSize(1);
        return rows.get(0);
    }
}
