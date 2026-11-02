package com.bizsim.sim;

import com.bizsim.run.RunRepository;
import com.bizsim.web.CashShortfallException;
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
    private final JdbcTemplate jdbc;

    public RoundService(RunRepository runs, RoundRepository rounds, ScenarioRepository scenarios,
                        ScoringEngine engine, DecisionValidator validator,
                        EnvironmentService environment, RunEventService events,
                        JdbcTemplate jdbc) {
        this.runs = runs;
        this.rounds = rounds;
        this.scenarios = scenarios;
        this.engine = engine;
        this.validator = validator;
        this.environment = environment;
        this.events = events;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ submit

    @Transactional
    public void submit(long tenantId, long userId, long runId, long teamId, DecisionPayload payload) {
        RunRepository.RunRow run = runs.find(runId, tenantId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such run"));
        if (!"OPEN".equals(run.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "run is " + run.status());
        }
        RoundRepository.RoundRow round = rounds.openRound(runId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.CONFLICT, "no round is open"));
        if (round.closesAt() != null && Instant.now().isAfter(round.closesAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "round " + round.number() + " closed at " + round.closesAt());
        }
        if (!rounds.teamBelongsToRun(teamId, runId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such company in this run");
        }
        if (!rounds.userOwnsTeam(userId, teamId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "you do not run this company");
        }

        Scenario scenario = scenarios.load(run.scenarioKey());
        ScoringEngine.TeamInput input = input(scenario, runId, round.id(), teamId, payload);

        List<String> errors = validator.validate(scenario, payload, input.rnd());
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        ScoringEngine.Commitment commitment = engine.preview(scenario,
                environment.forRound(scenario, runId, round.number()), input);
        if (commitment.plannedWorkers().compareTo(commitment.capacityAvailable()) > 0) {
            throw new ValidationException(List.of("production: the plan needs "
                    + commitment.plannedWorkers().stripTrailingZeros().toPlainString()
                    + " workers, this company has "
                    + commitment.capacityAvailable().stripTrailingZeros().toPlainString()
                    + " (hire production staff or buy capacity)"));
        }
        if (commitment.discretionary().compareTo(input.cash()) > 0) {
            Map<String, BigDecimal> lines = new LinkedHashMap<>();
            lines.put("materials", commitment.materials());
            lines.put("marketing", commitment.marketing());
            lines.put("rnd", commitment.rnd());
            lines.put("capacity", commitment.capacity());
            lines.put("hiring", commitment.hiring());
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
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such run"));

        Optional<RoundRepository.RoundRow> open = rounds.openRound(runId);
        if (open.isEmpty()) {
            return Optional.empty();
        }
        RoundRepository.RoundRow round = open.get();
        if (!rounds.markClosed(round.id())) {
            return Optional.empty();
        }

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

    /** Opens the next round, carrying the scenario's clock (D12). */
    @Transactional
    public void openRound(long runId, int number) {
        Scenario scenario = scenarios.load(scenarioKey(runId));
        rounds.insertRound(runId, number, scenario.params().roundMinutes());
        events.publishAfterCommit(runId, "round.opened", Map.of("round", number));
    }

    /** Seeds a new company with its product line, workforce and untouched R&D slate. */
    @Transactional
    public void initialiseCompany(long teamId, Scenario scenario) {
        rounds.initialiseCompany(teamId, scenario);
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<Map<String, Object>> scoreboard(long tenantId, long runId) {
        runs.find(runId, tenantId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such run"));
        return rounds.scoreboard(runId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> statements(long tenantId, long runId, long teamId) {
        runs.find(runId, tenantId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such run"));
        if (!rounds.teamBelongsToRun(teamId, runId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such company in this run");
        }
        return rounds.statements(runId, teamId);
    }

    /**
     * Everything the decision form needs for one company, and nothing it must not see: the R&D slate
     * carries the published estimate band, never the hidden expected cost.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> context(long tenantId, long runId, long teamId) {
        RunRepository.RunRow run = runs.find(runId, tenantId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such run"));
        if (!rounds.teamBelongsToRun(teamId, runId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such company in this run");
        }
        Scenario scenario = scenarios.load(run.scenarioKey());
        RoundRepository.TeamState team = rounds.teams(runId).stream()
                .filter(t -> t.id() == teamId).findFirst().orElseThrow();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("round", run.currentRound());
        rounds.openRound(runId).ifPresent(r -> body.put("closesAt", r.closesAt()));
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
        for (Scenario.ProjectSpec project : scenario.projects()) {
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
        return body;
    }

    /**
     * The paid market review (D18). It is bought with the round's decision, so it cannot be read
     * before paying, and it reports the ordinary economics only — never the event deck.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> marketReport(long tenantId, long runId, long teamId) {
        RunRepository.RunRow run = runs.find(runId, tenantId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such run"));
        if (!rounds.teamBelongsToRun(teamId, runId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such company in this run");
        }
        RoundRepository.RoundRow round = rounds.openRound(runId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.CONFLICT, "no round is open"));
        DecisionPayload decision = rounds.decision(round.id(), teamId).orElse(null);
        if (decision == null || !decision.marketReportRequested()) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "the market review has not been purchased for this round");
        }

        Scenario scenario = scenarios.load(run.scenarioKey());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchasedForRound", round.number());
        body.put("cost", scenario.params().marketReportCost());
        body.put("current", environment.conditions(scenario, runId, round.number()));
        body.put("forecast", environment.forecast(scenario, runId, round.number()));
        return body;
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
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such company"));
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
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such run"));
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
