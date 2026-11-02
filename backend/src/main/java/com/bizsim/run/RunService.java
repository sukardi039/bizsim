package com.bizsim.run;

import com.bizsim.run.RunRepository;
import com.bizsim.sim.RoundService;
import com.bizsim.sim.Scenario;
import com.bizsim.sim.ScenarioRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

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

    public record RunView(long id, String status, int currentRound, String scenarioKey,
                          List<RunRepository.TeamRow> teams,
                          List<RunRepository.PlayerRow> players) {
    }

    public record RoundState(int number, String status) {
    }

    private final RunRepository runs;
    private final RoundService rounds;
    private final ScenarioRepository scenarios;
    private final JdbcTemplate jdbc;

    public RunService(RunRepository runs, RoundService rounds, ScenarioRepository scenarios,
                      JdbcTemplate jdbc) {
        this.runs = runs;
        this.rounds = rounds;
        this.scenarios = scenarios;
        this.jdbc = jdbc;
    }

    @Transactional
    public RunView createRun(long tenantId, long facilitatorId, String name, String scenarioKey,
                             List<TeamSpec> teams) {
        if (!runs.scenarioExists(scenarioKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown scenario " + scenarioKey);
        }
        if (teams == null || teams.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at least one team is required");
        }
        if (teams.size() > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "at most 10 teams per run (decision 5)");
        }
        Scenario scenario = scenarios.load(scenarioKey);
        long simulationId = runs.insertSimulation(tenantId, name, scenarioKey, facilitatorId);
        long runId = runs.insertRun(tenantId, simulationId, scenarioKey);
        for (TeamSpec team : teams) {
            long teamId = runs.insertTeam(runId, blankToNull(team.name(), "team name"),
                    team.companyName(), scenario.params().startingCash(),
                    scenario.params().startingBrand());
            rounds.initialiseCompany(teamId, scenario);
        }
        return view(tenantId, runId);
    }

    @Transactional
    public RunView addTeam(long tenantId, long runId, TeamSpec spec) {
        RunRepository.RunRow run = runs.findForUpdate(runId, tenantId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such run"));
        if (!"OPEN".equals(run.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "teams cannot be added to a " + run.status() + " run");
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
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such team in this run"));
        if (!runs.userBelongsToTenant(userId, tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such user in this tenant");
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
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such run"));

        if (!"OPEN".equals(run.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "run is " + run.status() + ", rounds cannot advance");
        }

        int maxRounds = maxRounds(run.scenarioKey());
        int next = run.currentRound() + 1;
        if (next > maxRounds) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "scenario " + run.scenarioKey() + " has only " + maxRounds + " rounds");
        }

        // Advancing closes whatever is open and computes it. Closing an already-computed round is a
        // no-op by design, so a facilitator who clicks twice does not get two rounds of results.
        rounds.closeAndCompute(tenantId, runId);
        rounds.openRound(runId, next);
        runs.setCurrentRound(runId, next);

        if (next == maxRounds) {
            // Last round opened; the run still finishes explicitly so the debrief can be generated.
        }
        return new RoundState(next, "OPEN");
    }

    @Transactional(readOnly = true)
    public RunView get(long tenantId, long runId) {
        return view(tenantId, runId);
    }

    private RunView view(long tenantId, long runId) {
        RunRepository.RunRow run = requireRun(tenantId, runId);
        return new RunView(run.id(), run.status(), run.currentRound(), run.scenarioKey(),
                runs.teams(runId), runs.players(runId));
    }

    private RunRepository.RunRow requireRun(long tenantId, long runId) {
        return runs.find(runId, tenantId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such run"));
    }

    public int maxRounds(String scenarioKey) {
        Integer rounds = jdbc.query(
                "select (definition->>'rounds')::int from scenario where key = ?",
                (rs, i) -> (Integer) rs.getObject(1), scenarioKey).stream().findFirst().orElse(null);
        if (rounds == null || rounds < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scenario " + scenarioKey + " has no valid round count");
        }
        return rounds;
    }

    private static String blankToNull(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }
}
