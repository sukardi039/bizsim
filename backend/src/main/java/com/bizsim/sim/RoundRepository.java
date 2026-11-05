package com.bizsim.sim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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

    public record RoundRow(long id, long runId, int number, String status, java.time.Instant closesAt,
                           java.time.Instant pausedAt) {
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
                select id, run_id, number, status, closes_at, paused_at from round
                where run_id = ? and status = 'OPEN'
                """, (rs, i) -> new RoundRow(rs.getLong("id"), rs.getLong("run_id"), rs.getInt("number"),
                rs.getString("status"), rs.getTimestamp("closes_at") == null ? null
                : rs.getTimestamp("closes_at").toInstant(), rs.getTimestamp("paused_at") == null ? null
                : rs.getTimestamp("paused_at").toInstant()), runId).stream().findFirst();
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

    /** Rounds whose clock has run out, for the scheduler. A paused clock never runs out (D12). */
    public List<long[]> dueRounds() {
        return jdbc.query("""
                select r.run_id, u.tenant_id from round r join run u on u.id = r.run_id
                where r.status = 'OPEN' and r.paused_at is null
                  and r.closes_at is not null and r.closes_at <= now()
                """, (rs, i) -> new long[]{rs.getLong("run_id"), rs.getLong("tenant_id")});
    }

    // ------------------------------------------------------------------ facilitator powers

    /** Freezes the deadline. The remaining time is what `closes_at` still says. */
    public boolean pauseClock(long roundId) {
        return jdbc.update("""
                update round set paused_at = now()
                where id = ? and status = 'OPEN' and paused_at is null
                """, roundId) == 1;
    }

    /** Unfreezes it and pushes the deadline out by exactly the time the clock was stopped. */
    public boolean resumeClock(long roundId) {
        return jdbc.update("""
                update round
                set closes_at = case when closes_at is null then null
                                     else closes_at + (now() - paused_at) end,
                    paused_at = null
                where id = ? and paused_at is not null
                """, roundId) == 1;
    }

    /**
     * Adds minutes to the deadline. An untimed round (no `round_minutes`) gains a deadline here,
     * which is the useful reading of "extend": the class asked for time, so now it has some.
     */
    public boolean extendClock(long roundId, int minutes) {
        return jdbc.update("""
                update round set closes_at = coalesce(closes_at, now()) + (? * interval '1 minute')
                where id = ? and status = 'OPEN'
                """, minutes, roundId) == 1;
    }

    /** The most recent round that was computed: the only one a void may touch. */
    public Optional<RoundRow> lastComputed(long runId) {
        return jdbc.query("""
                select id, run_id, number, status, closes_at, paused_at from round
                where run_id = ? and status = 'COMPUTED' order by number desc limit 1
                """, (rs, i) -> new RoundRow(rs.getLong("id"), rs.getLong("run_id"), rs.getInt("number"),
                rs.getString("status"), rs.getTimestamp("closes_at") == null ? null
                : rs.getTimestamp("closes_at").toInstant(), rs.getTimestamp("paused_at") == null ? null
                : rs.getTimestamp("paused_at").toInstant()), runId).stream().findFirst();
    }

    /**
     * Records every company's state as the round opens its books. One SQL statement, so the snapshot
     * cannot disagree with the rows it copies: products, staff and the R&D slate are all taken at the
     * same instant.
     */
    public void writeSnapshots(long roundId, long runId) {
        jdbc.update("""
                insert into round_snapshot (round_id, team_id, state)
                select ?, t.id, jsonb_build_object(
                    'cash', t.cash, 'brand', t.brand_score, 'status', t.status,
                    'machineWorkers', t.machine_workers,
                    'cumulativeRevenue', t.cumulative_revenue,
                    'cumulativeProfit', t.cumulative_profit,
                    'lostSalesTotal', t.lost_sales_total,
                    'products', coalesce((select jsonb_agg(jsonb_build_object(
                            'product', cp.product_code, 'price', cp.unit_price,
                            'quality', cp.quality_level, 'unitCost', cp.unit_cost,
                            'stock', cp.stock))
                        from company_product cp where cp.team_id = t.id), '[]'::jsonb),
                    'staff', coalesce((select jsonb_object_agg(cs.function, cs.headcount)
                        from company_staff cs where cs.team_id = t.id), '{}'::jsonb),
                    'rnd', coalesce((select jsonb_agg(jsonb_build_object(
                            'project', cr.project_code, 'invested', cr.invested,
                            'monthsInvested', cr.months_invested, 'startedRound', cr.started_round,
                            'status', cr.status, 'benefitFactor', cr.benefit_factor,
                            'completedRound', cr.completed_round))
                        from company_rnd cr where cr.team_id = t.id), '[]'::jsonb))
                from team t where t.run_id = ?
                on conflict (round_id, team_id) do nothing
                """, roundId, runId);
    }

    public int snapshotTeams(long roundId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from round_snapshot where round_id = ?", Integer.class, roundId);
        return n == null ? 0 : n;
    }

    /** Puts every company back to the state recorded when the round was computed. */
    public void restoreSnapshots(long roundId) {
        jdbc.query("select team_id, state::text as state_text from round_snapshot where round_id = ?",
                rs -> {
                    long teamId = rs.getLong("team_id");
                    JsonNode state = readTree(rs.getString("state_text"));
                    restoreTeam(teamId, state);
                }, roundId);
    }

    private void restoreTeam(long teamId, JsonNode state) {
        String status = state.path("status").asText();
        if (!"ACTIVE".equals(status) && !"INSOLVENT".equals(status)) {
            // A snapshot that cannot name the company's state is corrupt; refusing beats writing a
            // status the table's check constraint will reject half-way through the restore.
            throw new IllegalStateException("round_snapshot for team " + teamId + " is unreadable");
        }
        jdbc.update("""
                update team set cash = ?, brand_score = ?, status = ?, machine_workers = ?,
                                cumulative_revenue = ?, cumulative_profit = ?, lost_sales_total = ?
                where id = ?
                """, dec(state, "cash"), dec(state, "brand"), status,
                dec(state, "machineWorkers"), dec(state, "cumulativeRevenue"),
                dec(state, "cumulativeProfit"), dec(state, "lostSalesTotal"), teamId);

        for (JsonNode product : state.path("products")) {
            jdbc.update("""
                    update company_product set unit_price = ?, quality_level = ?, unit_cost = ?, stock = ?
                    where team_id = ? and product_code = ?
                    """, dec(product, "price"), dec(product, "quality"), dec(product, "unitCost"),
                    dec(product, "stock"), teamId, product.path("product").asText());
        }
        state.path("staff").fields().forEachRemaining(entry -> jdbc.update("""
                insert into company_staff (team_id, function, headcount) values (?, ?, ?)
                on conflict (team_id, function) do update set headcount = excluded.headcount
                """, teamId, entry.getKey(), entry.getValue().asInt()));
        for (JsonNode rnd : state.path("rnd")) {
            jdbc.update("""
                    update company_rnd
                    set invested = ?, months_invested = ?, started_round = ?, status = ?,
                        benefit_factor = ?, completed_round = ?
                    where team_id = ? and project_code = ?
                    """, dec(rnd, "invested"), rnd.path("monthsInvested").asInt(),
                    rnd.path("startedRound").isNull() ? null : rnd.path("startedRound").asInt(),
                    rnd.path("status").asText(), dec(rnd, "benefitFactor"),
                    rnd.path("completedRound").isNull() ? null : rnd.path("completedRound").asInt(),
                    teamId, rnd.path("project").asText());
        }
    }

    /**
     * Deletes a round's results and reopens it. The decisions survive on purpose: the class is
     * correcting a month, not retyping it, and a player may still overwrite their saved decision
     * while the reopened round is open.
     */
    public boolean reopenVoidedRound(long roundId, Integer minutes) {
        jdbc.update("delete from round_result where round_id = ?", roundId);
        jdbc.update("delete from round_statement where round_id = ?", roundId);
        jdbc.update("delete from round_snapshot where round_id = ?", roundId);
        return jdbc.update("""
                update round
                set status = 'OPEN', closed_at = null, paused_at = null,
                    closes_at = case when ? > 0 then now() + (? * interval '1 minute') else null end
                where id = ? and status = 'COMPUTED'
                """, minutes == null ? 0 : minutes, minutes == null ? 0 : minutes, roundId) == 1;
    }

    // ------------------------------------------------------------------ decisions as seen by staff

    /** Every company and what it sent for one round, submitted or not. */
    public List<Map<String, Object>> decisionsOfRound(long runId, int roundNumber) {
        return jdbc.query("""
                select t.id as team_id, t.company_name, d.submitted_at, d.payload::text as payload,
                       u.display_name
                from team t
                left join round r on r.run_id = t.run_id and r.number = ?
                left join decision d on d.round_id = r.id and d.team_id = t.id
                left join app_user u on u.id = d.submitted_by
                where t.run_id = ?
                order by t.id
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("teamId", rs.getLong("team_id"));
            row.put("companyName", rs.getString("company_name"));
            row.put("submitted", rs.getTimestamp("submitted_at") != null);
            row.put("submittedAt", rs.getTimestamp("submitted_at") == null ? null
                    : rs.getTimestamp("submitted_at").toInstant());
            row.put("submittedBy", rs.getString("display_name"));
            String payload = rs.getString("payload");
            row.put("decision", payload == null ? null : readTree(payload));
            return row;
        }, roundNumber, runId);
    }

    // ------------------------------------------------------------------ audit trail

    public void audit(long tenantId, long runId, Integer roundNumber, Long teamId, String action,
                      BigDecimal amount, String reason, long actorId) {
        jdbc.update("""
                insert into facilitator_action
                    (tenant_id, run_id, round_number, team_id, action, amount, reason, actor_id)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, runId, roundNumber, teamId, action, amount, reason, actorId);
    }

    public List<Map<String, Object>> auditOf(long runId) {
        return jdbc.query("""
                select a.id, a.round_number, a.team_id, t.company_name, a.action, a.amount, a.reason,
                       u.display_name, a.created_at
                from facilitator_action a
                left join team t on t.id = a.team_id
                join app_user u on u.id = a.actor_id
                where a.run_id = ?
                order by a.id
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("round", (Integer) rs.getObject("round_number"));
            row.put("teamId", rs.getObject("team_id"));
            row.put("companyName", rs.getString("company_name"));
            row.put("action", rs.getString("action"));
            row.put("amount", rs.getBigDecimal("amount"));
            row.put("reason", rs.getString("reason"));
            row.put("actor", rs.getString("display_name"));
            row.put("at", rs.getTimestamp("created_at").toInstant());
            return row;
        }, runId);
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

    /**
     * Narrows a freshly created company to the product line its profile carries. Called only by
     * {@code RunService.startPrepared}, before the run is handed to anyone: once a decision names a
     * product, removing it would strand the company.
     */
    public void restrictProducts(long teamId, List<String> keep) {
        List<String> existing = jdbc.queryForList(
                "select product_code from company_product where team_id = ?", String.class, teamId);
        for (String code : existing) {
            if (!keep.contains(code)) {
                jdbc.update("delete from company_product where team_id = ? and product_code = ?",
                        teamId, code);
            }
        }
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
            jdbc.update("""
                    update company_product set unit_price = ?, quality_level = ?, stock = ?
                    where team_id = ? and product_code = ?
                    """, line.price(), line.quality(), line.closingStock(), outcome.teamId(),
                    line.product());
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
                    quality_cost, opening_cash, profit, cash_after, brand_after, lost_sales,
                    capacity_used, capacity_available, detail)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                on conflict (round_id, team_id) do nothing
                """, roundId, outcome.teamId(), outcome.revenue(), outcome.cogs(), outcome.payroll(),
                outcome.marketing(), outcome.rndSpend(), outcome.fixedCost(), outcome.holdingCost(),
                outcome.hiringCost(), outcome.capacityCost(), outcome.reportCost(),
                outcome.qualitySpend(),
                outcome.openingCash(), outcome.profit(), outcome.cash(), outcome.brand(),
                outcome.lostSales(), outcome.capacityUsed(), outcome.capacityAvailable(),
                detailJson(outcome));
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

    /** Moves one company's balance by hand and returns what it became. */
    public BigDecimal adjustCash(long teamId, BigDecimal delta) {
        return jdbc.queryForObject("update team set cash = cash + ? where id = ? returning cash",
                BigDecimal.class, delta, teamId);
    }

    /** The company a trainee is seated in for this run, if any: the scope of their own report (D21). */
    public Optional<Long> teamOfPlayer(long userId, long runId) {
        return jdbc.query("""
                select p.team_id from player p join team t on t.id = p.team_id
                where p.user_id = ? and t.run_id = ? order by p.id limit 1
                """, (rs, i) -> rs.getLong(1), userId, runId).stream().findFirst();
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
        // The detail is returned as an object, not as a string: it carries the per-product lines the
        // debrief shows (D34), and every caller would otherwise have to parse it again.
        return jdbc.query("""
                select r.number as round, s.revenue, s.cogs, s.payroll, s.marketing, s.rnd_spend,
                       s.fixed_cost, s.holding_cost, s.hiring_cost, s.capacity_cost, s.report_cost,
                       s.quality_cost, s.opening_cash, s.profit, s.cash_after, s.brand_after,
                       s.lost_sales, s.capacity_used,
                       s.capacity_available, s.detail as detail
                from round_statement s
                join round r on r.id = s.round_id
                where r.run_id = ? and s.team_id = ?
                order by r.number
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String money : List.of("revenue", "cogs", "payroll", "marketing", "rnd_spend",
                    "fixed_cost", "holding_cost", "hiring_cost", "capacity_cost", "report_cost",
                    "quality_cost", "opening_cash", "profit", "cash_after")) {
                row.put(money, rs.getBigDecimal(money));
            }
            row.put("round", rs.getInt("round"));
            row.put("brand_after", rs.getBigDecimal("brand_after"));
            row.put("lost_sales", rs.getBigDecimal("lost_sales"));
            row.put("capacity_used", rs.getBigDecimal("capacity_used"));
            row.put("capacity_available", rs.getBigDecimal("capacity_available"));
            row.put("detail", readJson(rs.getString("detail")));
            return row;
        }, runId, teamId);
    }

    private Object readJson(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readValue(text, Map.class);
        } catch (JsonProcessingException e) {
            // A statement that cannot be read is a stored-data fault, not a client error.
            throw new IllegalStateException("round_statement.detail is not valid json", e);
        }
    }

    private JsonNode readTree(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored json is not valid", e);
        }
    }

    private static BigDecimal dec(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? BigDecimal.ZERO : value.decimalValue();
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
