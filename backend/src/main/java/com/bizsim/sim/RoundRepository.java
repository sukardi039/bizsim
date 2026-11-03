package com.bizsim.sim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence for round state, company state and the monthly statement. */
@Repository
public class RoundRepository {

    public record RoundRow(long id, long runId, int number, String status, java.time.Instant closesAt) {
    }

    public record TeamState(long id, String name, String companyName, String status, BigDecimal cash,
                            BigDecimal brand, BigDecimal machineWorkers, BigDecimal cumulativeRevenue,
                            BigDecimal cumulativeProfit, BigDecimal lostSalesTotal) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RoundRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ rounds

    public Optional<RoundRow> openRound(long runId) {
        return jdbc.query("""
                select id, run_id, number, status, closes_at from round
                where run_id = ? and status = 'OPEN'
                """, (rs, i) -> new RoundRow(rs.getLong("id"), rs.getLong("run_id"), rs.getInt("number"),
                rs.getString("status"), rs.getTimestamp("closes_at") == null ? null
                : rs.getTimestamp("closes_at").toInstant()), runId).stream().findFirst();
    }

    /** D12: the round carries its own deadline; null minutes means a facilitator-only round. */
    public void insertRound(long runId, int number, Integer minutes) {
        if (minutes == null || minutes <= 0) {
            jdbc.update("insert into round (run_id, number, status) values (?, ?, 'OPEN')", runId, number);
        } else {
            jdbc.update("""
                    insert into round (run_id, number, status, closes_at)
                    values (?, ?, 'OPEN', now() + (? * interval '1 minute'))
                    """, runId, number, minutes);
        }
    }

    public boolean markClosed(long roundId) {
        return jdbc.update("""
                update round set status = 'CLOSED', closed_at = now()
                where id = ? and status = 'OPEN'
                """, roundId) == 1;
    }

    public void markComputed(long roundId) {
        jdbc.update("update round set status = 'COMPUTED' where id = ?", roundId);
    }

    /** Rounds whose clock has run out, for the scheduler. */
    public List<long[]> dueRounds() {
        return jdbc.query("""
                select r.run_id, u.tenant_id from round r join run u on u.id = r.run_id
                where r.status = 'OPEN' and r.closes_at is not null and r.closes_at <= now()
                """, (rs, i) -> new long[]{rs.getLong("run_id"), rs.getLong("tenant_id")});
    }

    // ------------------------------------------------------------------ company state

    public List<TeamState> teams(long runId) {
        return jdbc.query("""
                select id, name, company_name, status, cash, brand_score, machine_workers,
                       cumulative_revenue, cumulative_profit, lost_sales_total
                from team where run_id = ? order by id
                """, (rs, i) -> new TeamState(rs.getLong("id"), rs.getString("name"),
                rs.getString("company_name"), rs.getString("status"), rs.getBigDecimal("cash"),
                rs.getBigDecimal("brand_score"), rs.getBigDecimal("machine_workers"),
                rs.getBigDecimal("cumulative_revenue"), rs.getBigDecimal("cumulative_profit"),
                rs.getBigDecimal("lost_sales_total")), runId);
    }

    public Map<Long, Map<String, ScoringEngine.ProductState>> products(long runId) {
        Map<Long, Map<String, ScoringEngine.ProductState>> result = new LinkedHashMap<>();
        jdbc.query("""
                select cp.team_id, cp.product_code, cp.unit_price, cp.quality_level, cp.unit_cost, cp.stock
                from company_product cp join team t on t.id = cp.team_id
                where t.run_id = ?
                """, rs -> {
            result.computeIfAbsent(rs.getLong("team_id"), k -> new LinkedHashMap<>())
                    .put(rs.getString("product_code"), new ScoringEngine.ProductState(
                            rs.getString("product_code"), rs.getBigDecimal("unit_price"),
                            rs.getBigDecimal("quality_level"), rs.getBigDecimal("unit_cost"),
                            rs.getBigDecimal("stock")));
        }, runId);
        return result;
    }

    public Map<Long, Map<String, Integer>> staff(long runId) {
        Map<Long, Map<String, Integer>> result = new LinkedHashMap<>();
        jdbc.query("""
                select cs.team_id, cs.function, cs.headcount
                from company_staff cs join team t on t.id = cs.team_id
                where t.run_id = ?
                """, rs -> {
            result.computeIfAbsent(rs.getLong("team_id"), k -> new LinkedHashMap<>())
                    .put(rs.getString("function"), rs.getInt("headcount"));
        }, runId);
        return result;
    }

    public Map<Long, Map<String, ScoringEngine.RndState>> rnd(long runId) {
        Map<Long, Map<String, ScoringEngine.RndState>> result = new LinkedHashMap<>();
        jdbc.query("""
                select cr.team_id, cr.project_code, cr.invested, cr.months_invested, cr.started_round,
                       cr.status, cr.benefit_factor
                from company_rnd cr join team t on t.id = cr.team_id
                where t.run_id = ?
                """, rs -> {
            result.computeIfAbsent(rs.getLong("team_id"), k -> new LinkedHashMap<>())
                    .put(rs.getString("project_code"), new ScoringEngine.RndState(
                            rs.getString("project_code"), rs.getBigDecimal("invested"),
                            rs.getInt("months_invested"), (Integer) rs.getObject("started_round"),
                            rs.getString("status"), rs.getBigDecimal("benefit_factor")));
        }, runId);
        return result;
    }

    /** Seeds a new company: product line, workforce, and an untouched project slate. */
    public void initialiseCompany(long teamId, Scenario scenario) {
        for (Scenario.ProductSpec p : scenario.products()) {
            jdbc.update("""
                    insert into company_product (team_id, product_code, unit_price, quality_level, unit_cost, stock)
                    values (?, ?, ?, ?, ?, 0)
                    """, teamId, p.code(), p.basePrice(), p.baseQuality(), p.baseUnitCost());
        }
        for (Map.Entry<String, Integer> entry : scenario.params().startingStaff().entrySet()) {
            jdbc.update("insert into company_staff (team_id, function, headcount) values (?, ?, ?)",
                    teamId, entry.getKey(), entry.getValue());
        }
        for (Scenario.ProjectSpec project : scenario.projects()) {
            jdbc.update("""
                    insert into company_rnd (team_id, project_code, invested, months_invested, status)
                    values (?, ?, 0, 0, 'ACTIVE')
                    """, teamId, project.code());
        }
    }

    public void applyOutcome(long roundId, ScoringEngine.TeamOutcome outcome, BigDecimal cumulativeRevenue,
                             BigDecimal cumulativeProfit, BigDecimal lostSalesTotal) {
        jdbc.update("""
                update team set cash = ?, brand_score = ?, status = ?, machine_workers = ?,
                                cumulative_revenue = ?, cumulative_profit = ?, lost_sales_total = ?
                where id = ?
                """, outcome.cash(), outcome.brand(), outcome.status(), outcome.machineWorkers(),
                cumulativeRevenue, cumulativeProfit, lostSalesTotal, outcome.teamId());

        for (ScoringEngine.ProductLine line : outcome.lines()) {
            jdbc.update("update company_product set unit_price = ?, stock = ? where team_id = ? and product_code = ?",
                    line.price(), line.closingStock(), outcome.teamId(), line.product());
        }
        for (Map.Entry<String, Integer> entry : outcome.staff().entrySet()) {
            jdbc.update("""
                    insert into company_staff (team_id, function, headcount) values (?, ?, ?)
                    on conflict (team_id, function) do update set headcount = excluded.headcount
                    """, outcome.teamId(), entry.getKey(), entry.getValue());
        }
        for (ScoringEngine.RndUpdate update : outcome.rndUpdates()) {
            jdbc.update("""
                    update company_rnd
                    set invested = ?, months_invested = ?, started_round = ?, status = ?,
                        benefit_factor = ?, completed_round = ?
                    where team_id = ? and project_code = ?
                    """, update.invested(), update.monthsInvested(), update.startedRound(), update.status(),
                    update.benefitFactor(), update.completedRound(), outcome.teamId(), update.project());
        }
    }

    public void insertStatement(long roundId, ScoringEngine.TeamOutcome outcome) {
        jdbc.update("""
                insert into round_statement (round_id, team_id, revenue, cogs, payroll, marketing,
                    rnd_spend, fixed_cost, holding_cost, hiring_cost, capacity_cost, report_cost,
                    profit, cash_after, brand_after, lost_sales, capacity_used, capacity_available, detail)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                on conflict (round_id, team_id) do nothing
                """, roundId, outcome.teamId(), outcome.revenue(), outcome.cogs(), outcome.payroll(),
                outcome.marketing(), outcome.rndSpend(), outcome.fixedCost(), outcome.holdingCost(),
                outcome.hiringCost(), outcome.capacityCost(), outcome.reportCost(), outcome.profit(),
                outcome.cash(), outcome.brand(), outcome.lostSales(), outcome.capacityUsed(),
                outcome.capacityAvailable(), detailJson(outcome));
    }

    public void insertResult(long roundId, long teamId, Map<String, Object> score, BigDecimal totalScore) {
        jdbc.update("""
                insert into round_result (round_id, team_id, score, total_score)
                values (?, ?, ?::jsonb, ?)
                on conflict (round_id, team_id) do nothing
                """, roundId, teamId, toJson(score), totalScore);
    }

    // ------------------------------------------------------------------ decisions

    public Optional<DecisionPayload> decision(long roundId, long teamId) {
        return jdbc.query("select payload::text from decision where round_id = ? and team_id = ?",
                (rs, i) -> {
                    try {
                        return json.readValue(rs.getString(1), DecisionPayload.class);
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("stored decision is unreadable", e);
                    }
                }, roundId, teamId).stream().findFirst();
    }

    public void upsertDecision(long roundId, long teamId, DecisionPayload payload, long submittedBy) {
        jdbc.update("""
                insert into decision (round_id, team_id, role_key, payload, submitted_by)
                values (?, ?, 'OWNER', ?::jsonb, ?)
                on conflict (round_id, team_id) do update
                   set payload = excluded.payload, submitted_at = now(), submitted_by = excluded.submitted_by
                """, roundId, teamId, toJson(payload), submittedBy);
    }

    public boolean teamBelongsToRun(long teamId, long runId) {
        Boolean ok = jdbc.queryForObject(
                "select exists (select 1 from team where id = ? and run_id = ?)", Boolean.class, teamId, runId);
        return Boolean.TRUE.equals(ok);
    }

    public boolean userOwnsTeam(long userId, long teamId) {
        Boolean ok = jdbc.queryForObject(
                "select exists (select 1 from player where user_id = ? and team_id = ?)",
                Boolean.class, userId, teamId);
        return Boolean.TRUE.equals(ok);
    }

    // ------------------------------------------------------------------ reading results

    public List<Map<String, Object>> scoreboard(long runId, Integer round) {
        // Columns are extracted from the jsonb on purpose: returning a raw jsonb to Jackson yields a
        // PGobject, and the scoreboard contract should be stable fields, not whatever is in the blob.
        return jdbc.queryForList("""
                select r.number as round, rr.team_id, t.company_name,
                       (rr.score->>'profit')::numeric            as profit,
                       (rr.score->>'cumulativeProfit')::numeric  as cumulative_profit,
                       (rr.score->>'revenue')::numeric           as revenue,
                       (rr.score->>'marketShare')::numeric       as market_share,
                       (rr.score->>'cash')::numeric              as cash,
                       (rr.score->>'brand')::numeric             as brand,
                       (rr.score->>'stock')::numeric             as stock,
                       (rr.score->>'lostSales')::numeric         as lost_sales,
                       (rr.score->>'capacityUtilisation')::numeric as capacity_utilisation,
                       (rr.score->>'rndCompleted')::int          as rnd_completed,
                       rr.score->>'status'                       as status,
                       rr.total_score
                from round_result rr
                join round r on r.id = rr.round_id
                join team t on t.id = rr.team_id
                where r.run_id = ?
                  and r.number = coalesce(?, (select max(number) from round
                                              where run_id = ? and status = 'COMPUTED'))
                order by rr.total_score desc, t.id
                """, runId, round, runId);
    }

    public List<Map<String, Object>> statements(long runId, long teamId) {
        return jdbc.queryForList("""
                select r.number as round, s.revenue, s.cogs, s.payroll, s.marketing, s.rnd_spend,
                       s.fixed_cost, s.holding_cost, s.hiring_cost, s.capacity_cost, s.report_cost,
                       s.profit, s.cash_after, s.brand_after, s.lost_sales, s.capacity_used,
                       s.capacity_available, s.detail::text as detail
                from round_statement s
                join round r on r.id = s.round_id
                where r.run_id = ? and s.team_id = ?
                order by r.number
                """, runId, teamId);
    }

    // ------------------------------------------------------------------ json

    private String detailJson(ScoringEngine.TeamOutcome outcome) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("products", outcome.lines());
        detail.put("rnd", outcome.rndUpdates());
        detail.put("cashChange", outcome.cashChange());
        return toJson(detail);
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise statement", e);
        }
    }
}
