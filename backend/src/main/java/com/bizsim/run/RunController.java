package com.bizsim.run;

import com.bizsim.tenant.TenantContext;
import com.bizsim.web.ProblemException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    public record CreateRunRequest(String name, String scenarioKey, List<RunService.TeamSpec> teams,
                                   Integer rounds, Integer cycles) {
    }

    public record AddTeamRequest(String name, String companyName) {
    }

    public record AddPlayerRequest(long teamId, long userId, String roleKey) {
    }

    /** Everything was prepared in the workbench: a template, company profiles, and the trainees. */
    public record StartPreparedRequest(String name, String templateKey, List<Long> companyProfileIds,
                                       List<Long> traineeIds, Integer rounds, Integer cycles) {
    }

    private final RunService runs;

    public RunController(RunService runs) {
        this.runs = runs;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RunService.RunView create(@RequestBody CreateRunRequest request) {
        TenantContext.Principal me = requireFacilitator();
        return runs.createRun(me.tenantId(), me.userId(),
                request.name() == null || request.name().isBlank() ? "Untitled" : request.name(),
                request.scenarioKey(), request.teams(), request.rounds(), request.cycles());
    }

    /**
     * Starts a session from the prepared set: template + company profiles + seated trainees, in one
     * call. Nothing is written until every piece checks out.
     */
    @PostMapping("/prepared")
    @ResponseStatus(HttpStatus.CREATED)
    public RunService.RunView startPrepared(@RequestBody StartPreparedRequest request) {
        TenantContext.Principal me = requireFacilitator();
        return runs.startPrepared(me.tenantId(), me.userId(),
                request.name() == null || request.name().isBlank() ? "Session" : request.name(),
                request.templateKey(), request.companyProfileIds(), request.traineeIds(),
                request.rounds(), request.cycles());
    }

    @GetMapping
    public List<RunService.RunView> list() {
        TenantContext.Principal me = TenantContext.require();
        // Sessions are execution data: a builder prepares packages and does not watch classes run.
        if (!"FACILITATOR".equals(me.role()) && !"ADMIN".equals(me.role())) {
            throw ProblemException.forbidden("auth.facilitator_only", "sessions are for facilitators");
        }
        return runs.runs(me.tenantId(), me.userId(), me.role());
    }

    @GetMapping("/{id}")
    public RunService.RunView get(@PathVariable long id) {
        return runs.get(TenantContext.require().tenantId(), id);
    }

    /** Deletes a simulation and everything played in it. Owner or admin only; cannot be undone. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        TenantContext.Principal me = requireFacilitator();
        runs.deleteRun(me.tenantId(), me.userId(), me.role(), id);
    }

    @PostMapping("/{id}/teams")
    @ResponseStatus(HttpStatus.CREATED)
    public RunService.RunView addTeam(@PathVariable long id, @RequestBody AddTeamRequest request) {
        TenantContext.Principal me = requireFacilitator();
        return runs.addTeam(me.tenantId(), id,
                new RunService.TeamSpec(request.name(), request.companyName()));
    }

    @PostMapping("/{id}/players")
    @ResponseStatus(HttpStatus.CREATED)
    public RunRepository.PlayerRow addPlayer(@PathVariable long id,
                                             @RequestBody AddPlayerRequest request) {
        TenantContext.Principal me = requireFacilitator();
        return runs.addPlayer(me.tenantId(), id, request.teamId(), request.userId(), request.roleKey());
    }

    @PostMapping("/{id}/rounds/next")
    public RunService.RoundState nextRound(@PathVariable long id) {
        TenantContext.Principal me = requireFacilitator();
        return runs.openNextRound(me.tenantId(), id);
    }

    private static TenantContext.Principal requireFacilitator() {
        TenantContext.Principal me = TenantContext.require();
        if (!"FACILITATOR".equals(me.role()) && !"ADMIN".equals(me.role())) {
            throw ProblemException.forbidden("auth.facilitator_only", "facilitator or admin role required");
        }
        return me;
    }
}
