package com.bizsim.sim;

import com.bizsim.run.RunRepository;
import com.bizsim.web.ApiProblem;
import com.bizsim.web.CashShortfallException;
import com.bizsim.web.ProblemException;
import com.bizsim.web.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decisions in, statements out.
 *
 * <p>{@link #closeAndCompute} is the only place that writes {@code round_result} and company state,
 * and it is called from exactly two doors: the facilitator endpoint and the round clock. Both take
 * the same row lock and both are idempotent, so a click and a timer cannot compute a month twice.
 */
@Service
public class RoundService {

    private final RunRepository runs;
    private final RoundRepository rounds;
    private final ScenarioRepository scenarios;
    private final ScoringEngine engine;
    private final DecisionValidator validator;
    private final EnvironmentService environment;
    private final RunEventService events;
    private final com.bizsim.user.UserRepository users;
    private final JdbcTemplate jdbc;

    public RoundService(RunRepository runs, RoundRepository rounds, ScenarioRepository scenarios,
                        ScoringEngine engine, DecisionValidator validator,
                        EnvironmentService environment, RunEventService events,
                        JdbcTemplate jdbc, com.bizsim.user.UserRepository users) {
        this.runs = runs;
        this.rounds = rounds;
        this.scenarios = scenarios;
        this.engine = engine;
        this.validator = validator;
        this.environment = environment;
        this.events = events;
        this.users = users;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ submit

    @Transactional
    public void submit(long tenantId, long userId, long runId, long teamId, DecisionPayload payload) {
        RunRepository.RunRow run = runs.find(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
        if (!"OPEN".equals(run.status())) {
            throw ProblemException.conflict("run.not_open", "run is " + run.status(), "status", run.status());
        }
        RoundRepository.RoundRow round = rounds.openRound(runId).orElseThrow(
                () -> ProblemException.conflict("round.not_open", "no round is open"));
        // A paused clock is stopped, not expired: its stored deadline may already be in the past,
        // but the facilitator stopped time on purpose and the class may still submit.
        if (round.pausedAt() == null && round.closesAt() != null
                && Instant.now().isAfter(round.closesAt())) {
            throw ProblemException.conflict("round.closed",
                    "round " + round.number() + " closed at " + round.closesAt(),
                    "round", round.number());
        }
        if (!rounds.teamBelongsToRun(teamId, runId)) {
            throw ProblemException.notFound("team.not_found", "no such company in this run");
        }
        if (!rounds.userOwnsTeam(userId, teamId)) {
            throw ProblemException.forbidden("team.not_yours", "you do not run this company");
        }

        Scenario scenario = scenarios.load(run.scenarioKey());
        ScoringEngine.TeamInput input = input(scenario, runId, round.id(), teamId, payload);

        List<ApiProblem> problems = validator.validate(scenario, payload, input.rnd());
        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }

        ScoringEngine.Commitment commitment = engine.preview(scenario,
                environment.forRound(scenario, runId, round.number()), input);
        if (commitment.plannedWorkers().compareTo(commitment.capacityAvailable()) > 0) {
            throw new ValidationException(List.of(ApiProblem.of("production.workforce_short",
                    "production: the plan needs "
                            + commitment.plannedWorkers().stripTrailingZeros().toPlainString()
                            + " workers, this company has "
                            + commitment.capacityAvailable().stripTrailingZeros().toPlainString()
                            + " (hire production staff or buy capacity)",
                    "needed", commitment.plannedWorkers().stripTrailingZeros().toPlainString(),
                    "available", commitment.capacityAvailable().stripTrailingZeros().toPlainString())));
        }
        if (commitment.discretionary().compareTo(input.cash()) > 0) {
            Map<String, BigDecimal> lines = new LinkedHashMap<>();
            lines.put("materials", commitment.materials());
            lines.put("marketing", commitment.marketing());
            lines.put("rnd", commitment.rnd());
            lines.put("capacity", commitment.capacity());
            lines.put("hiring", commitment.hiring());
            if (commitment.quality().signum() > 0) {
                lines.put("quality", commitment.quality());
            }
            throw new CashShortfallException(input.cash(), commitment.discretionary(), lines);
        }

        rounds.upsertDecision(round.id(), teamId, payload, userId);
        events.publishAfterCommit(runId, "decision.submitted",
                Map.of("round", round.number(), "teamId", teamId));
    }

    // ------------------------------------------------------------------ close and compute

    /** Closes the open round of a run and computes it. No-op when nothing is open. */
    @Transactional
    public Optional<ScoringEngine.RoundResult> closeAndCompute(long tenantId, long runId) {
        RunRepository.RunRow run = runs.findForUpdate(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));

        Optional<RoundRepository.RoundRow> open = rounds.openRound(runId);
        if (open.isEmpty()) {
            return Optional.empty();
        }
        RoundRepository.RoundRow round = open.get();
        if (!rounds.markClosed(round.id())) {
            return Optional.empty();
        }

        // The state this round is about to overwrite, kept so "void the last round" can put it back
        // exactly. Written before any outcome is applied, in the same transaction as the results.
        rounds.writeSnapshots(round.id(), runId);

        Scenario scenario = scenarios.load(run.scenarioKey());
        EnvironmentService.RoundEnvironment environment =
                this.environment.forRound(scenario, runId, round.number());
        Map<Long, ScoringEngine.TeamInput> inputs = inputs(runId, round.id());
        List<ScoringEngine.TeamOutcome> outcomes = engine.compute(
                scenario, environment, new ArrayList<>(inputs.values())).teams();

        Map<Long, RoundRepository.TeamState> state = new LinkedHashMap<>();
        for (RoundRepository.TeamState team : rounds.teams(runId)) {
            state.put(team.id(), team);
        }

        BigDecimal pool = engine.marketPool(scenario, environment, outcomes.size());
        for (ScoringEngine.TeamOutcome outcome : outcomes) {
            RoundRepository.TeamState before = state.get(outcome.teamId());
            BigDecimal cumulativeRevenue = nz(before == null ? null : before.cumulativeRevenue())
                    .add(outcome.revenue());
            BigDecimal cumulativeProfit = nz(before == null ? null : before.cumulativeProfit())
                    .add(outcome.profit());
            BigDecimal lostSalesTotal = nz(before == null ? null : before.lostSalesTotal())
                    .add(outcome.lostSales());

            rounds.applyOutcome(round.id(), outcome, cumulativeRevenue, cumulativeProfit, lostSalesTotal);
            rounds.insertStatement(round.id(), outcome);
            rounds.insertResult(round.id(), outcome.teamId(),
                    score(scenario, outcome, cumulativeProfit, pool), cumulativeProfit);
        }

        rounds.markComputed(round.id());

        // D12/D21: computing the last round ends the session. Leaving it OPEN here had two effects —
        // the console kept offering a round that openNextRound refuses to open, and the D21 guard
        // (which counts every run that is not FINISHED as in flight) blocked the facilitator from
        // starting their next simulation. The debrief reads the same computed rows either way.
        int planned = run.rounds() == null ? round.number() : run.rounds();
        boolean lastRound = round.number() >= planned;
        if (lastRound) {
            runs.finishRun(runId);
        }

        List<Map<String, Object>> summary = new ArrayList<>();
        for (ScoringEngine.TeamOutcome outcome : outcomes) {
            summary.add(Map.of(
                    "teamId", outcome.teamId(),
                    "profit", outcome.profit(),
                    "cash", outcome.cash(),
                    "brand", outcome.brand()));
        }
        events.publishAfterCommit(runId, "round.closed",
                Map.of("round", round.number(), "teams", summary));

        return Optional.of(new ScoringEngine.RoundResult(outcomes));
    }

    public record ClockState(int round, Instant closesAt, boolean paused, long remainingSeconds) {
    }

    // ------------------------------------------------------------------ facilitator powers (D12)

    /**
     * Stop the clock. The deadline is frozen where it stands and the round clock sweep ignores the
     * row, so nothing computes while the class is talking.
     */
    @Transactional
    public ClockState pauseClock(long tenantId, long runId, long userId) {
        RunRepository.RunRow run = runs.findForUpdate(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
        RoundRepository.RoundRow round = rounds.openRound(runId).orElseThrow(
                () -> ProblemException.conflict("round.not_open", "no round is open"));
        if (round.pausedAt() != null) {
            return clock(round);
        }
        rounds.pauseClock(round.id());
        rounds.audit(tenantId, runId, round.number(), null, "CLOCK_PAUSE", null, null, userId);
        events.publishAfterCommit(runId, "clock.paused", Map.of("round", round.number()));
        return clock(rounds.openRound(runId).orElseThrow());
    }

    /** Start it again, giving back exactly the time it was stopped. */
    @Transactional
    public ClockState resumeClock(long tenantId, long runId, long userId) {
        RunRepository.RunRow run = runs.findForUpdate(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
        RoundRepository.RoundRow round = rounds.openRound(runId).orElseThrow(
                () -> ProblemException.conflict("round.not_open", "no round is open"));
        if (round.pausedAt() == null) {
            return clock(round);
        }
        rounds.resumeClock(round.id());
        rounds.audit(tenantId, runId, round.number(), null, "CLOCK_RESUME", null, null, userId);
        events.publishAfterCommit(runId, "clock.resumed", Map.of("round", round.number()));
        return clock(rounds.openRound(runId).orElseThrow());
    }

    /** Push the deadline out. An untimed round gains one, which is what "we need more time" means. */
    @Transactional
    public ClockState extendClock(long tenantId, long runId, long userId, int minutes) {
        if (minutes <= 0 || minutes > 24 * 60) {
            throw ProblemException.badRequest("clock.extend_out_of_range",
                    "minutes must be between 1 and 1440", "max", 1440);
        }
        runs.findForUpdate(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
        RoundRepository.RoundRow round = rounds.openRound(runId).orElseThrow(
                () -> ProblemException.conflict("round.not_open", "no round is open"));
        rounds.extendClock(round.id(), minutes);
        rounds.audit(tenantId, runId, round.number(), null, "CLOCK_EXTEND",
                new BigDecimal(minutes), "added " + minutes + " minutes", userId);
        events.publishAfterCommit(runId, "clock.extended", Map.of("round", round.number(), "minutes", minutes));
        return clock(rounds.openRound(runId).orElseThrow());
    }

    /** One company's cash moved by hand, with the reason the class will read in the audit trail. */
    @Transactional
    public Map<String, Object> adjustCash(long tenantId, long runId, long userId, long teamId,
                                          BigDecimal delta, String reason) {
        RunRepository.RunRow run = runs.findForUpdate(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
        if ("FINISHED".equals(run.status())) {
            throw ProblemException.conflict("run.finished", "the session has finished");
        }
        if (delta == null || delta.signum() == 0) {
            throw ProblemException.badRequest("cash.delta_required",
                    "the adjustment must be a non-zero amount", "field", "delta");
        }
        String note = reason == null ? "" : reason.trim();
        if (note.length() < 3) {
            // D12/Q-G: the reason is mandatory. An unexplained cash change is not auditable.
            throw ProblemException.badRequest("cash.reason_required",
                    "a reason is required for a cash adjustment", "field", "reason");
        }
        if (!rounds.teamBelongsToRun(teamId, runId)) {
            throw ProblemException.notFound("team.not_found", "no such company in this run");
        }
        BigDecimal cash = rounds.adjustCash(teamId, delta);
        Integer number = rounds.openRound(runId).map(RoundRepository.RoundRow::number)
                .orElse(run.currentRound());
        rounds.audit(tenantId, runId, number, teamId, "CASH_ADJUST", delta, note, userId);
        events.publishAfterCommit(runId, "cash.adjusted",
                Map.of("teamId", teamId, "delta", delta, "cash", cash));
        return Map.of("teamId", teamId, "cash", cash, "delta", delta);
    }

    /**
     * Undo the last computed round: put every company back where it stood, delete that round's
     * results and statements, and reopen it so the class can correct the month and compute again.
     *
     * <p>The undo reads {@code round_snapshot}, written when the round was computed. Rounds computed
     * before that table existed carry no snapshot and are refused rather than half-restored.
     */
    @Transactional
    public Map<String, Object> voidLastRound(long tenantId, long runId, long userId, String reason) {
        RunRepository.RunRow run = runs.findForUpdate(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
        if (rounds.openRound(runId).isPresent()) {
            throw ProblemException.conflict("round.still_open",
                    "compute or close the open round before voiding the previous one");
        }
        RoundRepository.RoundRow round = rounds.lastComputed(runId).orElseThrow(
                () -> ProblemException.conflict("round.none_computed", "no computed round to void"));
        int companies = rounds.teams(runId).size();
        if (rounds.snapshotTeams(round.id()) < companies) {
            throw ProblemException.conflict("round.no_snapshot",
                    "round " + round.number() + " was computed before voiding existed and cannot be "
                            + "restored", "round", round.number());
        }

        rounds.restoreSnapshots(round.id());
        int minutes = scenarios.load(run.scenarioKey()).params().roundMinutes();
        rounds.reopenVoidedRound(round.id(), minutes);
        runs.reopenRun(runId);
        rounds.audit(tenantId, runId, round.number(), null, "ROUND_VOID",
                null, reason == null || reason.isBlank() ? null : reason.trim(), userId);
        events.publishAfterCommit(runId, "round.reopened", Map.of("round", round.number()));
        return Map.of("round", round.number(), "status", "OPEN");
    }

    /**
     * Put the session back to its pre-start condition (D58): every company at the state recorded when
     * the books opened, every round gone, month 0, standings hidden. Teams and seats survive, so the
     * class does not have to be re-registered — this is "start the month again", not "new session".
     *
     * <p>Rounds computed before snapshots existed cannot be rewound; a run with no computed round has
     * nothing to rewind and only loses its decisions.
     */
    @Transactional
    public Map<String, Object> resetRun(long tenantId, long runId, long userId, String reason) {
        RunRepository.RunRow run = runs.findForUpdate(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));

        int companies = rounds.teams(runId).size();
        int restored = 0;
        java.util.Optional<RoundRepository.RoundRow> first = rounds.earliest(runId);
        if (first.isPresent()) {
            if ("COMPUTED".equals(first.get().status())
                    && rounds.snapshotTeams(first.get().id()) < companies) {
                throw ProblemException.conflict("round.no_snapshot",
                        "round 1 was computed before resets existed, so the starting state cannot be "
                                + "restored", "round", first.get().number());
            }
            if ("COMPUTED".equals(first.get().status())) {
                // Round 1's snapshot is the state the session started from.
                rounds.restoreSnapshots(first.get().id());
            }
            restored = companies;
        }

        Integer openedRounds = jdbc.queryForObject("select count(*) from round where run_id = ?",
                Integer.class, runId);
        rounds.deleteRounds(runId);
        runs.resetToStart(runId);
        rounds.audit(tenantId, runId, null, null, "RUN_RESET", null,
                reason == null || reason.isBlank() ? null : reason.trim(), userId);
        events.publishAfterCommit(runId, "run.reset",
                Map.of("rounds", openedRounds == null ? 0 : openedRounds, "companies", restored));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roundsRemoved", openedRounds == null ? 0 : openedRounds);
        body.put("companiesRestored", restored);
        return body;
    }

    /** Every company's submission for one round — or for the open one — as the facilitator sees it. */
    @Transactional(readOnly = true)
    public Map<String, Object> submissions(long tenantId, long runId, Integer roundNumber) {
        RunRepository.RunRow run = runs.find(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
        int number = roundNumber != null ? roundNumber
                : rounds.openRound(runId).map(RoundRepository.RoundRow::number)
                .orElse(run.currentRound());
        if (number < 1) {
            throw ProblemException.conflict("round.not_open", "no round has been opened yet");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("round", number);
        body.put("companies", rounds.decisionsOfRound(runId, number));
        return body;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> audit(long tenantId, long runId) {
        runs.find(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
        return rounds.auditOf(runId);
    }

    /** The company a trainee is seated in, so their own report can be scoped to it (D21/D43). */
    @Transactional(readOnly = true)
    public Optional<Long> teamOfPlayer(long userId, long runId) {
        return rounds.teamOfPlayer(userId, runId);
    }

    /** The clock of the open round, for the console header. Empty when nothing is open. */
    @Transactional(readOnly = true)
    public Optional<ClockState> openClock(long runId) {
        return rounds.openRound(runId).map(RoundService::clock);
    }

    private static ClockState clock(RoundRepository.RoundRow round) {
        Instant deadline = round.closesAt();
        if (deadline == null) {
            return new ClockState(round.number(), null, round.pausedAt() != null, -1);
        }
        Instant until = round.pausedAt() != null ? round.pausedAt() : Instant.now();
        long remaining = Math.max(0, deadline.getEpochSecond() - until.getEpochSecond());
        return new ClockState(round.number(), deadline, round.pausedAt() != null, remaining);
    }

    /** Opens the next round, carrying the scenario's clock (D12). */
    @Transactional
    public void openRound(long runId, int number) {
        Scenario scenario = scenarios.load(scenarioKey(runId));
        rounds.insertRound(runId, number, scenario.params().roundMinutes());
        events.publishAfterCommit(runId, "round.opened", Map.of("round", number));
    }

    /** Narrows a freshly created company to the product line its profile carries (S6e). */
    public void restrictProducts(long teamId, List<String> keep) {
        rounds.restrictProducts(teamId, keep);
    }

    /** Seeds a new company with its product line, workforce and untouched R&D slate. */
    @Transactional
    public void initialiseCompany(long teamId, Scenario scenario) {
        rounds.initialiseCompany(teamId, scenario);
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<Map<String, Object>> scoreboard(long tenantId, long runId, Integer round) {
        runs.find(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
        // One row per company, for one round: the latest computed round unless a round is asked for.
        return rounds.scoreboard(runId, round);
    }

    /**
     * The standings a trainee may read (D51/D75): rank, company, cash after the round and that round's
     * profit. Staff see the same table; a person who is not seated in the session sees nothing at all.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> standings(long tenantId, long userId, String role, long runId,
                                               Integer round) {
        runs.find(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
        boolean staff = List.of("FACILITATOR", "ADMIN", "BUILDER").contains(role);
        if (!staff) {
            if (!rounds.userInRun(userId, runId)) {
                throw ProblemException.forbidden("team.not_yours", "you are not seated in this session");
            }
            // D52: the facilitator decides when the class may see the table. Hidden reads as an empty
            // table, not an error — the trainee console simply has nothing to show.
            if (!runs.find(runId, tenantId).map(RunRepository.RunRow::standingsVisible).orElse(false)) {
                return List.of();
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : rounds.standings(runId, round)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("rank", ((Number) row.get("rank")).intValue());
            view.put("teamId", ((Number) row.get("team_id")).longValue());
            view.put("company", row.get("company_name"));
            // D75: the rank is by cash, so cash is part of the row — a rank a trainee cannot account
            // for is worse than no rank.
            view.put("cash", row.get("cash"));
            view.put("profit", row.get("profit"));
            view.put("own", rounds.userOwnsTeam(userId, ((Number) row.get("team_id")).longValue()));
            rows.add(view);
        }
        return rows;
    }

    /**
     * D52: show or hide the class table. It is a session control like pausing the clock, so it is
     * audited — a class can ask when the ranking became visible.
     */
    @Transactional
    public boolean setStandingsVisible(long tenantId, long actorId, long runId, boolean visible) {
        runs.find(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
        runs.setStandingsVisible(runId, visible);
        rounds.audit(tenantId, runId, null, null, visible ? "STANDINGS_SHOW" : "STANDINGS_HIDE",
                null, null, actorId);
        return visible;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> statements(long tenantId, long userId, String role,
                                             long runId, long teamId) {
        runs.find(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
        requireTeamReadable(userId, role, runId, teamId);
        return rounds.statements(runId, teamId);
    }

    /**
     * Everything the decision form needs for one company, and nothing it must not see: the R&D slate
     * carries the published estimate band, never the hidden expected cost.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> context(long tenantId, long userId, String role,
                                       long runId, long teamId) {
        RunRepository.RunRow run = runs.find(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
        requireTeamReadable(userId, role, runId, teamId);
        Scenario scenario = scenarios.load(run.scenarioKey());
        RoundRepository.TeamState team = rounds.teams(runId).stream()
                .filter(t -> t.id() == teamId).findFirst().orElseThrow();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        // Whether a round is actually open, not just which one the run has reached. Before the
        // facilitator opens round 1 the run sits at round 0 with nothing to decide -- the form used
        // to render anyway and offer "Submit round 0", which the server then refused.
        Optional<RoundRepository.RoundRow> open = rounds.openRound(runId);
        body.put("round", run.currentRound());
        body.put("roundOpen", open.isPresent());
        open.ifPresent(r -> body.put("closesAt", r.closesAt()));

        // What this company already sent for the open round, if anything. Without it the trainee
        // cannot tell a submitted month from an empty one, and reloading the form silently loses
        // their input -- the decision is an upsert until the round closes, so the form can reopen on
        // the values they saved and let them correct them.
        Optional<DecisionPayload> submitted = open.flatMap(r -> rounds.decision(r.id(), teamId));
        body.put("submitted", submitted.isPresent());
        submitted.ifPresent(d -> body.put("decision", d));

        body.put("cash", team.cash());
        body.put("brand", team.brand());
        body.put("status", team.status());
        body.put("machineWorkers", team.machineWorkers());

        List<Map<String, Object>> products = new ArrayList<>();
        Map<String, ScoringEngine.ProductState> line = rounds.products(runId).getOrDefault(teamId, Map.of());
        for (Scenario.ProductSpec spec : scenario.products()) {
            ScoringEngine.ProductState state = line.get(spec.code());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", spec.code());
            row.put("name", spec.name());
            row.put("price", state == null ? spec.basePrice() : state.price());
            row.put("unitCost", state == null ? spec.baseUnitCost() : state.unitCost());
            row.put("quality", state == null ? spec.baseQuality() : state.quality());
            row.put("stock", state == null ? BigDecimal.ZERO : state.stock());
            row.put("unitsPerWorker", spec.unitsPerWorker());
            products.add(row);
        }
        body.put("products", products);

        Map<String, Integer> staff = rounds.staff(runId).getOrDefault(teamId, Map.of());
        body.put("staff", staff);
        body.put("salaries", scenario.params().salaries());
        body.put("fixedCostPerMonth", scenario.params().fixedCostPerMonth());
        body.put("holdingCostPerUnit", scenario.params().holdingCostPerUnit());
        body.put("channels", scenario.channels());
        body.put("machineCostPerWorker", scenario.params().machineCostPerWorker());

        Map<String, ScoringEngine.RndState> rnd = rounds.rnd(runId).getOrDefault(teamId, Map.of());
        List<Map<String, Object>> projects = new ArrayList<>();
        // D64: the catalogue has one set per language; a class sees only its own, so the choice stays
        // five projects rather than ten.
        String classLang = runs.langOf(runId);
        for (Scenario.ProjectSpec project : scenario.projects()) {
            if (!classLang.equals(project.lang())) {
                continue;
            }
            ScoringEngine.RndState state = rnd.get(project.code());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", project.code());
            row.put("name", project.name());
            row.put("targetProduct", project.targetProduct());
            row.put("advantageType", project.advantageType());
            row.put("advantageValue", project.advantageValue());
            row.put("durationMonths", project.durationMonths());
            // D17: the terms of the programme are public; only its cost is hidden.
            row.put("partialBenefit", project.partialBenefit());
            row.put("benefitCurve", project.benefitCurve());
            row.put("benefitExponent", project.benefitExponent());
            row.put("overfunding", project.overfunding());
            row.put("hintLow", estimateLow(project));
            row.put("hintHigh", estimateHigh(project));
            row.put("invested", state == null ? BigDecimal.ZERO : state.invested());
            row.put("monthsInvested", state == null ? 0 : state.monthsInvested());
            row.put("status", state == null ? "ACTIVE" : state.status());
            projects.add(row);
        }
        body.put("rndProjects", projects);
        body.put("market", environment.conditions(scenario, runId, run.currentRound()));
        body.put("marketReportCost", scenario.params().marketReportCost());
        // D15: the form shows the quality input only when this template sells the mechanic, and it
        // must know the price per point to show what a purchase would cost.
        body.put("qualityUpgrade", scenario.params().qualityUpgrade());
        body.put("qualityCostPerPoint", scenario.params().qualityCostPerPoint());
        return body;
    }

    /**
     * The paid market review (D18). It is bought with the round's decision, so it cannot be read
     * before paying, and it reports the ordinary economics only — never the event deck.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> marketReport(long tenantId, long userId, String role,
                                            long runId, long teamId) {
        RunRepository.RunRow run = runs.find(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
        requireTeamReadable(userId, role, runId, teamId);
        RoundRepository.RoundRow round = rounds.openRound(runId).orElseThrow(
                () -> ProblemException.conflict("round.not_open", "no round is open"));
        DecisionPayload decision = rounds.decision(round.id(), teamId).orElse(null);
        if (decision == null || !decision.marketReportRequested()) {
            throw new ProblemException(HttpStatus.PAYMENT_REQUIRED,
                    ApiProblem.of("report.not_purchased",
                            "the market review has not been purchased for this round"));
        }

        Scenario scenario = scenarios.load(run.scenarioKey());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchasedForRound", round.number());
        body.put("cost", scenario.params().marketReportCost());
        body.put("current", environment.conditions(scenario, runId, round.number()));
        body.put("forecast", environment.forecast(scenario, runId, round.number()));
        return body;
    }

    /**
     * A trainee may read only their own company: cash, stock, decisions and statements are exactly
     * the competitive information the simulation is about, so changing the team id in the URL must
     * not reveal a rival's position. Facilitators and admins run the whole class and may read any
     * company inside their tenant.
     */
    private void requireTeamReadable(long userId, String role, long runId, long teamId) {
        if (!rounds.teamBelongsToRun(teamId, runId)) {
            throw ProblemException.notFound("team.not_found", "no such company in this run");
        }
        if ("PLAYER".equals(role) && !rounds.userOwnsTeam(userId, teamId)) {
            throw ProblemException.forbidden("team.not_yours", "you do not run this company");
        }
    }

    // ------------------------------------------------------------------ internals

    private Map<Long, ScoringEngine.TeamInput> inputs(long runId, long roundId) {
        Map<Long, Map<String, ScoringEngine.ProductState>> products = rounds.products(runId);
        Map<Long, Map<String, Integer>> staff = rounds.staff(runId);
        Map<Long, Map<String, ScoringEngine.RndState>> rnd = rounds.rnd(runId);
        Map<Long, ScoringEngine.TeamInput> result = new LinkedHashMap<>();
        for (RoundRepository.TeamState team : rounds.teams(runId)) {
            DecisionPayload decision = rounds.decision(roundId, team.id()).orElse(null);
            result.put(team.id(), new ScoringEngine.TeamInput(team.id(), team.status(), nz(team.cash()), nz(team.brand()),
                    nz(team.machineWorkers()), staff.getOrDefault(team.id(), Map.of()),
                    products.getOrDefault(team.id(), Map.of()), rnd.getOrDefault(team.id(), Map.of()),
                    decision));
        }
        return result;
    }

    private ScoringEngine.TeamInput input(Scenario scenario, long runId, long roundId, long teamId, DecisionPayload payload) {
        RoundRepository.TeamState team = rounds.teams(runId).stream()
                .filter(t -> t.id() == teamId).findFirst().orElseThrow(
                        () -> ProblemException.notFound("team.not_found", "no such company"));
        return new ScoringEngine.TeamInput(team.id(), team.status(), nz(team.cash()), nz(team.brand()),
                nz(team.machineWorkers()), rounds.staff(runId).getOrDefault(teamId, Map.of()),
                rounds.products(runId).getOrDefault(teamId, Map.of()),
                rounds.rnd(runId).getOrDefault(teamId, Map.of()), payload);
    }

    private Map<String, Object> score(Scenario scenario, ScoringEngine.TeamOutcome outcome,
                                      BigDecimal cumulativeProfit, BigDecimal pool) {
        BigDecimal sold = outcome.lines().stream().map(ScoringEngine.ProductLine::sold)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal stock = outcome.stock().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal share = pool.signum() == 0 ? BigDecimal.ZERO
                : sold.multiply(new BigDecimal("100")).divide(pool, 2, java.math.RoundingMode.HALF_UP);
        BigDecimal utilisation = outcome.capacityAvailable().signum() == 0 ? BigDecimal.ZERO
                : outcome.capacityUsed().multiply(new BigDecimal("100"))
                .divide(outcome.capacityAvailable(), 2, java.math.RoundingMode.HALF_UP);

        Map<String, Object> score = new LinkedHashMap<>();
        score.put("profit", outcome.profit());
        score.put("cumulativeProfit", cumulativeProfit);
        score.put("revenue", outcome.revenue());
        score.put("marketShare", share);
        score.put("unitsSold", sold);
        score.put("cash", outcome.cash());
        score.put("brand", outcome.brand());
        score.put("stock", stock);
        score.put("lostSales", outcome.lostSales());
        score.put("capacityUtilisation", utilisation);
        score.put("rndCompleted", outcome.rndUpdates().stream()
                .filter(r -> "COMPLETED".equals(r.status())).count());
        score.put("status", outcome.status());
        return score;
    }

    private String scenarioKey(long runId) {
        return jdbc.query("select scenario_key from run where id = ?",
                (rs, i) -> rs.getString(1), runId).stream().findFirst().orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
    }

    private static BigDecimal estimateLow(Scenario.ProjectSpec project) {
        BigDecimal band = nz(project.hintBand());
        return project.hiddenCost().multiply(BigDecimal.ONE.subtract(band))
                .setScale(0, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal estimateHigh(Scenario.ProjectSpec project) {
        BigDecimal band = nz(project.hintBand());
        return project.hiddenCost().multiply(BigDecimal.ONE.add(band))
                .setScale(0, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
