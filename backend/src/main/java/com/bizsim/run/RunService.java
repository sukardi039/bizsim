package com.bizsim.run;

import com.bizsim.run.RunRepository;
import com.bizsim.sim.EnvironmentService;
import com.bizsim.sim.RoundService;
import com.bizsim.sim.Scenario;
import com.bizsim.sim.ScenarioRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import com.bizsim.user.UserRepository;
import com.bizsim.web.ProblemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Owns the round state machine and the writes that must happen together.
 *
 * <p>The only legal transitions are {@code OPEN → CLOSED} per round and {@code current_round += 1}
 * per advance. Both live in {@link #openNextRound} behind a row lock, called exclusively from the
 * facilitator endpoint — no client value ever writes {@code round.status} or {@code run.current_round}.
 */
@Service
public class RunService {

    public record TeamSpec(String name, String companyName) {
    }

    /**
     * @param roundOpen whether a round is open right now. The console shows one button whose job
     *                  follows this: compute while a round is open, open the next one when it is not
     *                  (D38). `currentRound` alone cannot tell those apart.
     */
    public record RunView(long id, String status, int currentRound, String scenarioKey,
                          int rounds, int cycles, String locale,
                          List<RunRepository.TeamRow> teams,
                          List<RunRepository.PlayerRow> players,
                          boolean roundOpen,
                          /** The open round's deadline, so the console can count down (D12). */
                          java.time.Instant closesAt,
                          boolean clockPaused,
                          /** Seconds left, already frozen while paused; -1 for an untimed round. */
                          long clockRemainingSeconds) {
    }

    public record RoundState(int number, String status) {
    }

    private final RunRepository runs;
    private final RoundService rounds;
    private final ScenarioRepository scenarios;
    private final EnvironmentService environment;
    private final JdbcTemplate jdbc;
    private final CompanyProfileRepository profiles;
    private final UserRepository users;
    private final PackageRepository packages;

    public RunService(RunRepository runs, RoundService rounds, ScenarioRepository scenarios,
                      EnvironmentService environment, JdbcTemplate jdbc,
                      CompanyProfileRepository profiles, UserRepository users,
                      PackageRepository packages) {
        this.runs = runs;
        this.rounds = rounds;
        this.scenarios = scenarios;
        this.environment = environment;
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.users = users;
        this.packages = packages;
    }

    @Transactional
    public RunView createRun(long tenantId, long facilitatorId, String name, String scenarioKey,
                             List<TeamSpec> teams, Integer requestedRounds, Integer requestedCycles) {
        if (!runs.scenarioExists(scenarioKey)) {
            throw ProblemException.badRequest("template.unknown", "unknown scenario " + scenarioKey, "template", scenarioKey);
        }
        if (teams == null || teams.isEmpty()) {
            throw ProblemException.badRequest("run.teams_required", "at least one team is required");
        }
        if (teams.size() > 10) {
            throw ProblemException.badRequest("run.too_many_teams",
                    "at most 10 teams per run (decision 5)", "max", 10);
        }
        // D21: one facilitator, one simulation at a time. A second concurrent simulation needs a
        // second facilitator account, so this is a refusal rather than a filter on the screen.
        if (runs.activeRunsOfFacilitator(tenantId, facilitatorId) > 0) {
            throw ProblemException.conflict("run.already_in_progress",
                    "you already have a simulation in progress; finish it, or sign in as another "
                            + "facilitator for a second one");
        }

        Scenario scenario = scenarios.load(scenarioKey);

        // D19: cycles are chosen when the simulation is set up, and rounds can be stretched if the
        // cohort needs more time than the default two cycles give it.
        int cycles = requestedCycles == null ? scenario.params().cycles() : requestedCycles;
        int defaultRounds = Math.max(1, cycles) * Math.max(1, scenario.params().cycleLength());
        int roundCount = requestedRounds == null ? defaultRounds : requestedRounds;
        if (cycles < 1 || roundCount < 1 || roundCount > 48) {
            throw ProblemException.badRequest("run.rounds_out_of_range",
                    "rounds must be 1-48 and cycles at least 1");
        }

        long simulationId = runs.insertSimulation(tenantId, name, scenarioKey, facilitatorId);
        long runId = runs.insertRun(tenantId, simulationId, scenarioKey, roundCount, cycles);
        // The economy and the event deck are drawn once, here, and frozen for this run (D18/D19).
        environment.generateTimeline(scenario, runId, roundCount);
        environment.scheduleEvents(scenario, runId);
        for (TeamSpec team : teams) {
            long teamId = runs.insertTeam(runId, blankToNull(team.name(), "team name"),
                    team.companyName(), scenario.params().startingCash(),
                    scenario.params().startingBrand());
            rounds.initialiseCompany(teamId, scenario);
        }
        return view(tenantId, runId);
    }

    /**
     * Starts a session from what was prepared: a template, company profiles, and the trainees to seat.
     *
     * <p>This is the workbench's pay-off (S6e): the facilitator no longer types company names into a
     * blank run. It creates the run exactly like {@link #createRun}, then narrows each company to the
     * product line its profile carries and seats the trainees in order.
     *
     * <p>Everything is validated before anything is written: an unknown template, a profile from
     * another tenant, a product code the template does not have, or an inactive trainee is a refusal,
     * not a half-built session.
     */
    @Transactional
    public RunView startPrepared(long tenantId, long facilitatorId, String name, String templateKey,
                                 List<Long> profileIds, List<Long> traineeIds,
                                 Integer requestedRounds, Integer requestedCycles) {
        // A package's template is a frozen copy, which the library hides on purpose; existence is the
        // right question here, not visibility.
        if (!scenarios.existsForTenant(tenantId, templateKey)) {
            throw ProblemException.notFound("template.not_found", "no such template");
        }
        if (profileIds == null || profileIds.isEmpty()) {
            throw ProblemException.badRequest("run.teams_required", "at least one company is required");
        }
        if (traineeIds != null && traineeIds.size() > profileIds.size()) {
            throw ProblemException.badRequest("run.too_many_trainees",
                    "there are more trainees than companies to seat them in",
                    "trainees", traineeIds.size(), "companies", profileIds.size());
        }

        Scenario scenario = scenarios.load(templateKey);
        List<CompanyProfileRepository.ProfileRow> chosen = new ArrayList<>();
        for (Long profileId : profileIds) {
            CompanyProfileRepository.ProfileRow profile = profiles.findForTenant(tenantId, profileId)
                    .orElseThrow(() -> ProblemException.notFound("company_profile.not_found",
                            "no such company profile in this tenant"));
            for (String code : profile.products()) {
                if (!scenario.productsByCode().containsKey(code)) {
                    throw ProblemException.badRequest("run.unknown_product",
                            "company " + profile.name() + " carries " + code
                                    + ", which template " + templateKey + " does not have",
                            "product", code, "company", profile.name());
                }
            }
            chosen.add(profile);
        }

        List<Long> seats = traineeIds == null ? List.of() : traineeIds;
        for (Long traineeId : seats) {
            if (traineeId == null) {
                continue; // a company left open for a walk-in
            }
            users.findInTenant(tenantId, traineeId)
                    .filter(user -> "PLAYER".equals(user.role()) && user.active())
                    .orElseThrow(() -> ProblemException.badRequest("user.not_found",
                            "no active trainee with id " + traineeId + " in this tenant"));
        }

        List<TeamSpec> teams = new ArrayList<>();
        for (CompanyProfileRepository.ProfileRow profile : chosen) {
            teams.add(new TeamSpec(profile.name(),
                    profile.companyName() == null || profile.companyName().isBlank()
                            ? profile.name() : profile.companyName()));
        }

        RunView view = createRun(tenantId, facilitatorId, name, templateKey, teams,
                requestedRounds, requestedCycles);

        for (int i = 0; i < chosen.size(); i++) {
            List<String> productLine = chosen.get(i).products();
            if (!productLine.isEmpty()) {
                rounds.restrictProducts(view.teams().get(i).id(), productLine);
            }
        }
        for (int i = 0; i < seats.size(); i++) {
            if (seats.get(i) != null) {
                addPlayer(tenantId, view.id(), view.teams().get(i).id(), seats.get(i), "OWNER");
            }
        }
        return view(tenantId, view.id());
    }

    /**
     * Starts a session from a published package (ruling of 3 Nov 2026).
     *
     * <p>The facilitator picks a package and seats trainees; the template, the companies and their
     * product lines come from the package. They cannot change any of it — that is the builder's job
     * and it happened earlier. The run records which package it came from, so a cohort can always be
     * traced back to the preparation that produced it.
     */
    @Transactional
    public RunView startFromPackage(long tenantId, long facilitatorId, long packageId, String name,
                                    List<Long> traineeIds, Integer requestedRounds,
                                    Integer requestedCycles) {
        PackageRepository.PackageRow pkg = packages.find(tenantId, packageId).orElseThrow(
                () -> ProblemException.notFound("package.not_found", "no such package"));
        if (!"PUBLISHED".equals(pkg.status())) {
            throw ProblemException.conflict("package.retired",
                    "this package has been retired; ask your builder for a current one",
                    "package", pkg.name());
        }
        // The class list was prepared with the package (ruling 3 Nov 2026). An explicit list still
        // wins, so a facilitator can seat a late arrival without republishing.
        List<Long> seats = new ArrayList<>();
        for (int i = 0; i < pkg.companyProfileIds().size(); i++) {
            seats.add(null);
        }
        for (PackageRepository.Seat seat : pkg.seats()) {
            if (seat.position() < seats.size()) {
                seats.set(seat.position(), seat.userId());
            }
        }
        List<Long> effective = traineeIds == null || traineeIds.isEmpty() ? seats : traineeIds;

        RunView view = startPrepared(tenantId, facilitatorId, name, pkg.templateKey(),
                pkg.companyProfileIds(), effective, requestedRounds, requestedCycles);
        jdbc.update("update run set package_id = ? where id = ?", packageId, view.id());
        return view(tenantId, view.id());
    }

    @Transactional
    public RunView addTeam(long tenantId, long runId, TeamSpec spec) {
        RunRepository.RunRow run = runs.findForUpdate(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
        if (!"OPEN".equals(run.status())) {
            throw ProblemException.conflict("run.not_open",
                    "teams cannot be added to a " + run.status() + " run", "status", run.status());
        }
        Scenario scenario = scenarios.load(run.scenarioKey());
        long teamId = runs.insertTeam(runId, blankToNull(spec.name(), "team name"), spec.companyName(),
                scenario.params().startingCash(), scenario.params().startingBrand());
        rounds.initialiseCompany(teamId, scenario);
        return view(tenantId, runId);
    }

    @Transactional
    public RunRepository.PlayerRow addPlayer(long tenantId, long runId, long teamId, long userId,
                                             String roleKey) {
        requireRun(tenantId, runId);
        runs.findTeam(teamId, runId).orElseThrow(
                () -> ProblemException.notFound("team.not_found", "no such team in this run"));
        if (!runs.userBelongsToTenant(userId, tenantId)) {
            throw ProblemException.notFound("user.not_found", "no such user in this tenant");
        }
        runs.insertPlayer(runId, teamId, userId, blankToNull(roleKey, "roleKey"));
        return runs.players(runId).stream()
                .filter(p -> p.userId() == userId)
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public RoundState openNextRound(long tenantId, long runId) {
        RunRepository.RunRow run = runs.findForUpdate(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));

        if (!"OPEN".equals(run.status())) {
            throw ProblemException.conflict("run.not_open",
                    "run is " + run.status() + ", rounds cannot advance", "status", run.status());
        }

        int maxRounds = run.rounds() == null ? maxRounds(run.scenarioKey()) : run.rounds();
        int next = run.currentRound() + 1;
        if (next > maxRounds) {
            throw ProblemException.conflict("template.rounds_exceeded",
                    "scenario " + run.scenarioKey() + " has only " + maxRounds + " rounds",
                    "rounds", maxRounds);
        }

        // Advancing closes whatever is open and computes it. Closing an already-computed round is a
        // no-op by design, so a facilitator who clicks twice does not get two rounds of results.
        rounds.closeAndCompute(tenantId, runId);
        rounds.openRound(runId, next);
        runs.setCurrentRound(runId, next);

        if (next == maxRounds) {
            // Opening the last round is not the end of the session: the run is finished when that
            // round is computed (RoundService.closeAndCompute), so the debrief has its last numbers.
        }
        return new RoundState(next, "OPEN");
    }

    @Transactional(readOnly = true)
    public RunView get(long tenantId, long runId) {
        return view(tenantId, runId);
    }

    /**
     * Removes a simulation a facilitator no longer wants — a botched setup, a rehearsal, a run that
     * was never finished. Only its owner, or an admin, may do it: the data is gone for good, which is
     * also why this is the only delete the API offers.
     */
    @Transactional
    public void deleteRun(long tenantId, long userId, String role, long runId) {
        requireRun(tenantId, runId);
        if (!"ADMIN".equals(role)) {
            long owner = runs.facilitatorOf(tenantId, runId).orElseThrow(
                    () -> ProblemException.notFound("run.not_found", "no such run"));
            if (owner != userId) {
                throw ProblemException.forbidden("run.not_yours",
                        "this simulation belongs to another facilitator");
            }
        }
        if (!runs.deleteRun(tenantId, runId)) {
            throw ProblemException.notFound("run.not_found", "no such run");
        }
    }

    /** Runs of the caller's tenant, newest first. Feeds the console sidebar. */
    @Transactional(readOnly = true)
    public List<RunView> runs(long tenantId, long userId, String role) {
        List<RunView> views = new ArrayList<>();
        for (RunRepository.RunRow run : runs.findVisible(tenantId, userId, role)) {
            views.add(view(tenantId, run.id()));
        }
        return views;
    }

    private RunView view(long tenantId, long runId) {
        RunRepository.RunRow run = requireRun(tenantId, runId);
        int totalRounds = run.rounds() == null ? maxRounds(run.scenarioKey()) : run.rounds();
        int cycles = run.cycles() == null ? 0 : run.cycles();
        Optional<RoundService.ClockState> clock = rounds.openClock(runId);
        return new RunView(run.id(), run.status(), run.currentRound(), run.scenarioKey(),
                totalRounds, cycles, runs.localeOf(runId), runs.teams(runId), runs.players(runId),
                clock.isPresent(),
                clock.map(RoundService.ClockState::closesAt).orElse(null),
                clock.map(RoundService.ClockState::paused).orElse(false),
                clock.map(RoundService.ClockState::remainingSeconds).orElse(-1L));
    }

    private RunRepository.RunRow requireRun(long tenantId, long runId) {
        return runs.find(runId, tenantId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
    }

    public int maxRounds(String scenarioKey) {
        Integer rounds = jdbc.query(
                "select (definition->>'rounds')::int from scenario where key = ?",
                (rs, i) -> (Integer) rs.getObject(1), scenarioKey).stream().findFirst().orElse(null);
        if (rounds == null || rounds < 1) {
            throw ProblemException.badRequest("template.invalid_rounds",
                    "scenario " + scenarioKey + " has no valid round count", "template", scenarioKey);
        }
        return rounds;
    }

    private static String blankToNull(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ProblemException.badRequest("request.field_required", field + " is required", "field", field);
        }
        return value.trim();
    }
}
