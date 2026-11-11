package com.bizsim.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The auto worker's reading and writing (D47–D50): who has to move, what the company looks like,
 * and the trace of what was decided.
 *
 * <p>Everything here reads the same rows a trainee's own screen reads — `team`, `company_product`,
 * `company_staff`, `company_rnd` and the previous round's statement — which is what keeps "same
 * information" a property of the query rather than of the policy's good manners.
 */
@Repository
public class BotRepository {

    /** A seat that has to act: which round, which company, which brain. */
    public record Due(long roundId, int roundNumber, long runId, long tenantId, String scenarioKey,
                      long teamId, long userId, long profileId, String profileKey, String level,
                      String personality, int profileVersion, String paramsJson,
                      String companyName, Instant openedAt, Integer roundMinutes, Instant closesAt) {
    }

    /** The company as it stands before this month's decision. */
    public record State(BigDecimal cash, BigDecimal brand, BigDecimal machineWorkers,
                        String status, Map<String, Integer> staff,
                        Map<String, ScoringEngine.ProductState> products,
                        Map<String, ScoringEngine.RndState> rnd,
                        List<ScoringEngine.ProductLine> lastLines) {
    }

    public record TraceRow(int round, long teamId, String company, String profileKey,
                           int profileVersion, String level, String personality, String mode,
                           Instant decidedAt, String rationale, String payload) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public BotRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ who has to move

    /**
     * Open rounds where an auto-played company has neither a decision nor a trace. A paused clock is
     * excluded: the facilitator stopped time on purpose.
     */
    public List<Due> due(Long tenantId, Long runId) {
        return jdbc.query("""
                select r.id as round_id, r.number as round_number, r.opened_at, r.closes_at,
                       run.id as run_id, run.tenant_id, run.scenario_key,
                       p.team_id, p.user_id, b.id as profile_id, b.key as profile_key, b.level,
                       b.personality, b.version as profile_version, b.params::text as params,
                       t.company_name,
                       null::integer as round_minutes
                from round r
                join run on run.id = r.run_id and run.status = 'OPEN'
                join player p on p.run_id = r.run_id and p.bot_profile_id is not null
                join bot_profile b on b.id = p.bot_profile_id and b.active
                join team t on t.id = p.team_id
                where r.status = 'OPEN' and r.paused_at is null
                  and (?::bigint is null or run.id = ?::bigint)
                  and (?::bigint is null or run.tenant_id = ?::bigint)
                  and not exists (select 1 from decision d
                                  where d.round_id = r.id and d.team_id = p.team_id)
                  and not exists (select 1 from bot_decision bd
                                  where bd.round_id = r.id and bd.team_id = p.team_id)
                order by r.number, t.id
                """, (rs, i) -> new Due(
                rs.getLong("round_id"), rs.getInt("round_number"), rs.getLong("run_id"),
                rs.getLong("tenant_id"), rs.getString("scenario_key"), rs.getLong("team_id"),
                rs.getLong("user_id"), rs.getLong("profile_id"), rs.getString("profile_key"),
                rs.getString("level"), rs.getString("personality"), rs.getInt("profile_version"),
                rs.getString("params"), rs.getString("company_name"),
                ts(rs.getTimestamp("opened_at")), (Integer) rs.getObject("round_minutes"),
                ts(rs.getTimestamp("closes_at"))), runId, runId, tenantId, tenantId);
    }

    // ------------------------------------------------------------------ state

    /** The company as it stands now: the same rows the trainee's form is built from. */
    public State state(Scenario scenario, long runId, int roundNumber, long teamId) {
        Map<String, Object> team = jdbc.queryForMap(
                "select cash, brand_score, machine_workers, status from team where id = ?", teamId);
        Map<String, Integer> staff = new LinkedHashMap<>();
        jdbc.query("select function, headcount from company_staff where team_id = ?",
                rs -> {
                    staff.put(rs.getString(1), rs.getInt(2));
                }, teamId);
        Map<String, ScoringEngine.ProductState> products = new LinkedHashMap<>();
        jdbc.query("""
                select product_code, unit_price, quality_level, unit_cost, stock, stock_value
                from company_product where team_id = ? order by product_code
                """, rs -> {
            products.put(rs.getString(1), new ScoringEngine.ProductState(rs.getString(1),
                    rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                    rs.getBigDecimal(5), rs.getBigDecimal(6)));
        }, teamId);
        Map<String, ScoringEngine.RndState> rnd = new LinkedHashMap<>();
        jdbc.query("""
                select project_code, invested, months_invested, started_round, status, benefit_factor
                from company_rnd where team_id = ? order by project_code
                """, rs -> {
            rnd.put(rs.getString(1), new ScoringEngine.RndState(rs.getString(1), rs.getBigDecimal(2),
                    rs.getInt(3), (Integer) rs.getObject(4), rs.getString(5), rs.getBigDecimal(6)));
        }, teamId);

        return new State((BigDecimal) team.get("cash"), (BigDecimal) team.get("brand_score"),
                (BigDecimal) team.get("machine_workers"), (String) team.get("status"), staff, products,
                rnd, lastLines(runId, roundNumber - 1, teamId));
    }

    /** The previous computed round's per-product lines, which is what the policy forecasts from. */
    public List<ScoringEngine.ProductLine> lastLines(long runId, int roundNumber, long teamId) {
        if (roundNumber < 1) {
            return List.of();
        }
        List<String> payloads = jdbc.query("""
                select st.detail::text from round_statement st
                join round r on r.id = st.round_id
                where r.run_id = ? and r.number = ? and st.team_id = ?
                """, (rs, i) -> rs.getString(1), runId, roundNumber, teamId);
        if (payloads.isEmpty()) {
            return List.of();
        }
        try {
            JsonNode products = json.readTree(payloads.get(0)).path("products");
            List<ScoringEngine.ProductLine> lines = new ArrayList<>();
            for (JsonNode node : products) {
                lines.add(new ScoringEngine.ProductLine(node.path("product").asText(),
                        dec(node, "planned"), dec(node, "produced"), dec(node, "openingStock"),
                        dec(node, "demand"), dec(node, "sold"), dec(node, "closingStock"),
                        dec(node, "lost"), dec(node, "price"), dec(node, "unitCost"),
                        dec(node, "quality"), dec(node, "revenue"), dec(node, "cogs"),
                        dec(node, "averageCost"), dec(node, "stockValue")));
            }
            return lines;
        } catch (Exception e) {
            throw new IllegalStateException("stored statement is unreadable", e);
        }
    }

    /** The pre-round state written when the books opened: what a replay has to reason from. */
    public Optional<State> snapshot(Scenario scenario, long runId, int roundNumber, long teamId) {
        List<String> states = jdbc.query("""
                select s.state::text from round_snapshot s
                join round r on r.id = s.round_id
                where r.run_id = ? and r.number = ? and s.team_id = ?
                """, (rs, i) -> rs.getString(1), runId, roundNumber, teamId);
        if (states.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode node = json.readTree(states.get(0));
            Map<String, Integer> staff = new LinkedHashMap<>();
            node.path("staff").fields().forEachRemaining(e -> staff.put(e.getKey(), e.getValue().asInt()));
            Map<String, ScoringEngine.ProductState> products = new LinkedHashMap<>();
            for (JsonNode p : node.path("products")) {
                String code = p.path("product").asText();
                products.put(code, new ScoringEngine.ProductState(code, dec(p, "price"),
                        dec(p, "quality"), dec(p, "unitCost"), dec(p, "stock"),
                        dec(p, "stockValue")));
            }
            Map<String, ScoringEngine.RndState> rnd = new LinkedHashMap<>();
            for (JsonNode p : node.path("rnd")) {
                rnd.put(p.path("project").asText(), new ScoringEngine.RndState(
                        p.path("project").asText(), dec(p, "invested"), p.path("monthsInvested").asInt(),
                        p.path("startedRound").isNull() || p.path("startedRound").isMissingNode() ? null
                                : p.path("startedRound").asInt(),
                        p.path("status").asText("ACTIVE"), dec(p, "benefitFactor")));
            }
            return Optional.of(new State(dec(node, "cash"), dec(node, "brand"),
                    dec(node, "machineWorkers"), node.path("status").asText("ACTIVE"), staff, products,
                    rnd, lastLines(runId, roundNumber - 1, teamId)));
        } catch (Exception e) {
            throw new IllegalStateException("stored snapshot is unreadable", e);
        }
    }

    // ------------------------------------------------------------------ the trace

    public void record(long roundId, long teamId, String profileKey, int profileVersion,
                       String paramsJson, String paramsDigest, String seedKey, String contextDigest,
                       String rationale, String mode) {
        jdbc.update("""
                insert into bot_decision (round_id, team_id, profile_key, profile_version, params,
                                          params_digest, seed_key, context_digest, rationale, mode)
                values (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                on conflict (round_id, team_id) do nothing
                """, roundId, teamId, profileKey, profileVersion, paramsJson, paramsDigest, seedKey,
                contextDigest, rationale, mode);
    }

    public List<TraceRow> trace(long tenantId, long runId) {
        return jdbc.query("""
                select r.number as round_number, bd.team_id, t.company_name, bd.profile_key,
                       bd.profile_version, coalesce(bp.level, '?') as level,
                       coalesce(bp.personality, '?') as personality, bd.mode, bd.decided_at,
                       bd.rationale, null::text as payload
                from bot_decision bd
                join round r on r.id = bd.round_id
                join run on run.id = r.run_id
                join team t on t.id = bd.team_id
                left join bot_profile bp on bp.key = bd.profile_key and bp.tenant_id = run.tenant_id
                where r.run_id = ? and run.tenant_id = ?
                order by r.number, t.id
                """, (rs, i) -> new TraceRow(rs.getInt("round_number"), rs.getLong("team_id"),
                rs.getString("company_name"), rs.getString("profile_key"),
                rs.getInt("profile_version"), rs.getString("level"), rs.getString("personality"),
                rs.getString("mode"), ts(rs.getTimestamp("decided_at")), rs.getString("rationale"),
                null), runId, tenantId);
    }

    public BotRoundService.BotProfileView profile(long tenantId, long profileId) {
        return jdbc.query("""
                select level, personality, params::text from bot_profile where tenant_id = ? and id = ?
                """, (rs, i) -> new BotRoundService.BotProfileView(rs.getString(1), rs.getString(2),
                rs.getString(3)), tenantId, profileId).stream().findFirst()
                .orElseThrow(() -> com.bizsim.web.ProblemException.notFound("bot.not_found",
                        "no such auto worker"));
    }

    public Optional<Integer> botProfileOf(long runId, long teamId) {
        return jdbc.query("select bot_profile_id from player where run_id = ? and team_id = ? "
                        + "and bot_profile_id is not null", (rs, i) -> rs.getInt(1), runId, teamId)
                .stream().findFirst();
    }

    private static BigDecimal dec(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? BigDecimal.ZERO : value.decimalValue();
    }

    private static Instant ts(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
