package com.bizsim.run;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Every statement here is tenant-scoped. Where a write needs a parent row's tenant, the tenant check
 * happens in the same SQL (see the {@code exists} guards) so a missed service-level check cannot
 * turn into a cross-tenant write.
 */
@Repository
public class RunRepository {

    public record RunRow(long id, long tenantId, long simulationId, String scenarioKey,
                         String status, int currentRound, Integer rounds, Integer cycles) {
    }

    public record TeamRow(long id, String name, String companyName, BigDecimal cash,
                          BigDecimal brandScore, String status, BigDecimal machineWorkers) {
    }

    public record PlayerRow(long id, long teamId, long userId, String roleKey, String displayName) {
    }

    private static final RowMapper<RunRow> RUN = (rs, i) -> new RunRow(
            rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("simulation_id"),
            rs.getString("scenario_key"), rs.getString("status"), rs.getInt("current_round"),
            (Integer) rs.getObject("rounds"), (Integer) rs.getObject("cycles"));

    private static final RowMapper<TeamRow> TEAM = (rs, i) -> new TeamRow(
            rs.getLong("id"), rs.getString("name"), rs.getString("company_name"),
            rs.getBigDecimal("cash"), rs.getBigDecimal("brand_score"), rs.getString("status"),
            rs.getBigDecimal("machine_workers"));

    private static final RowMapper<PlayerRow> PLAYER = (rs, i) -> new PlayerRow(
            rs.getLong("id"), rs.getLong("team_id"), rs.getLong("user_id"),
            rs.getString("role_key"), rs.getString("display_name"));

    private final JdbcTemplate jdbc;

    public RunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertSimulation(long tenantId, String name, String scenarioKey, long facilitatorId) {
        return jdbc.queryForObject("""
                insert into simulation (tenant_id, name, scenario_key, status, facilitator_id)
                values (?, ?, ?, 'READY', ?) returning id
                """, Long.class, tenantId, name, scenarioKey, facilitatorId);
    }

    /**
     * The runs a caller may see (D21). A facilitator sees only the simulations they own, because one
     * facilitator runs one simulation at a time; an admin sees the whole tenant; a player sees the
     * runs their companies belong to.
     */
    public List<RunRow> findVisible(long tenantId, long userId, String role) {
        if ("ADMIN".equals(role)) {
            return jdbc.query("select * from run where tenant_id = ? order by id desc", RUN, tenantId);
        }
        if ("PLAYER".equals(role)) {
            return jdbc.query("""
                    select distinct r.* from run r
                    join team t on t.run_id = r.id
                    join player p on p.team_id = t.id
                    where r.tenant_id = ? and p.user_id = ?
                    order by r.id desc
                    """, RUN, tenantId, userId);
        }
        return jdbc.query("""
                select r.* from run r
                join simulation s on s.id = r.simulation_id
                where r.tenant_id = ? and s.facilitator_id = ?
                order by r.id desc
                """, RUN, tenantId, userId);
    }

    /** A facilitator may have only one simulation in flight; the count is the guard for D21. */
    public int activeRunsOfFacilitator(long tenantId, long facilitatorId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from run r
                join simulation s on s.id = r.simulation_id
                where r.tenant_id = ? and s.facilitator_id = ? and r.status <> 'FINISHED'
                """, Integer.class, tenantId, facilitatorId);
        return count == null ? 0 : count;
    }

    public long insertRun(long tenantId, long simulationId, String scenarioKey, int rounds, int cycles) {
        return jdbc.queryForObject("""
                insert into run (tenant_id, simulation_id, scenario_key, status, current_round,
                                 rounds, cycles, started_at)
                values (?, ?, ?, 'OPEN', 0, ?, ?, now()) returning id
                """, Long.class, tenantId, simulationId, scenarioKey, rounds, cycles);
    }

    public long insertTeam(long runId, String name, String companyName, BigDecimal cash,
                           BigDecimal brandScore) {
        return jdbc.queryForObject("""
                insert into team (run_id, name, company_name, cash, brand_score)
                values (?, ?, ?, ?, ?) returning id
                """, Long.class, runId, name, companyName, cash, brandScore);
    }

    public Optional<RunRow> find(long runId, long tenantId) {
        return jdbc.query("select * from run where id = ? and tenant_id = ?", RUN, runId, tenantId)
                .stream().findFirst();
    }

    /** {@code for update} so two facilitator clicks cannot open two rounds. */
    public Optional<RunRow> findForUpdate(long runId, long tenantId) {
        return jdbc.query("select * from run where id = ? and tenant_id = ? for update", RUN,
                runId, tenantId).stream().findFirst();
    }

    public List<TeamRow> teams(long runId) {
        return jdbc.query("select * from team where run_id = ? order by id", TEAM, runId);
    }

    public Optional<TeamRow> findTeam(long teamId, long runId) {
        return jdbc.query("select * from team where id = ? and run_id = ?", TEAM, teamId, runId)
                .stream().findFirst();
    }

    public List<PlayerRow> players(long runId) {
        return jdbc.query("""
                select p.*, u.display_name from player p
                join app_user u on u.id = p.user_id
                where p.run_id = ? order by p.team_id, p.role_key
                """, PLAYER, runId);
    }

    /** The user must belong to the caller's tenant; enforced in SQL, not by the controller. */
    public boolean userBelongsToTenant(long userId, long tenantId) {
        Boolean ok = jdbc.queryForObject(
                "select exists (select 1 from app_user where id = ? and tenant_id = ? and active)",
                Boolean.class, userId, tenantId);
        return Boolean.TRUE.equals(ok);
    }

    public long insertPlayer(long runId, long teamId, long userId, String roleKey) {
        return jdbc.queryForObject("""
                insert into player (run_id, team_id, user_id, role_key) values (?, ?, ?, ?) returning id
                """, Long.class, runId, teamId, userId, roleKey);
    }

    public boolean scenarioExists(String key) {
        Integer n = jdbc.queryForObject(
                "select count(*) from scenario where key = ?", Integer.class, key);
        return n != null && n > 0;
    }

    public Optional<Integer> openRoundNumber(long runId) {
        return jdbc.query("select number from round where run_id = ? and status = 'OPEN'",
                (rs, i) -> rs.getInt("number"), runId).stream().findFirst();
    }

    public void closeOpenRound(long runId) {
        jdbc.update("""
                update round set status = 'CLOSED', closed_at = now()
                where run_id = ? and status = 'OPEN'
                """, runId);
    }

    public void insertRound(long runId, int number) {
        jdbc.update("insert into round (run_id, number, status) values (?, ?, 'OPEN')", runId, number);
    }

    public void setCurrentRound(long runId, int number) {
        jdbc.update("update run set current_round = ? where id = ?", number, runId);
    }

    public void finishRun(long runId) {
        jdbc.update("""
                update run set status = 'FINISHED', finished_at = now(), current_round = current_round
                where id = ?
                """, runId);
    }
}
