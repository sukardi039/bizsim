package com.bizsim.sim;

import com.bizsim.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Player-facing decision endpoints and the shared read models. */
@RestController
@RequestMapping("/api/runs/{runId}")
public class SimulationController {

    private final RoundService rounds;

    public SimulationController(RoundService rounds) {
        this.rounds = rounds;
    }

    @PostMapping("/teams/{teamId}/decisions")
    public Map<String, Object> submit(@PathVariable long runId, @PathVariable long teamId,
                                      @RequestBody DecisionPayload payload) {
        TenantContext.Principal me = TenantContext.require();
        if (!"PLAYER".equals(me.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "player role required");
        }
        rounds.submit(me.tenantId(), me.userId(), runId, teamId, payload);
        return Map.of("submitted", true);
    }

    /** Form data for one company, including the R&D slate without its hidden cost. */
    @GetMapping("/teams/{teamId}/context")
    public Map<String, Object> context(@PathVariable long runId, @PathVariable long teamId) {
        TenantContext.Principal me = TenantContext.require();
        return rounds.context(me.tenantId(), me.userId(), me.role(), runId, teamId);
    }

    @GetMapping("/teams/{teamId}/statements")
    public List<Map<String, Object>> statements(@PathVariable long runId, @PathVariable long teamId) {
        TenantContext.Principal me = TenantContext.require();
        return rounds.statements(me.tenantId(), me.userId(), me.role(), runId, teamId);
    }

    /** The paid forecast (D18). 402 until the round's decision buys it. */
    @GetMapping("/teams/{teamId}/market-report")
    public Map<String, Object> marketReport(@PathVariable long runId, @PathVariable long teamId) {
        TenantContext.Principal me = TenantContext.require();
        return rounds.marketReport(me.tenantId(), me.userId(), me.role(), runId, teamId);
    }

    @GetMapping("/scoreboard")
    public List<Map<String, Object>> scoreboard(@PathVariable long runId,
                                                @RequestParam(required = false) Integer round) {
        TenantContext.Principal me = TenantContext.require();
        return rounds.scoreboard(me.tenantId(), runId, round);
    }

    /** Facilitator power "close a round" without opening the next one (D12). */
    @PostMapping("/rounds/close")
    public ResponseEntity<ScoringEngine.RoundResult> close(@PathVariable long runId) {
        TenantContext.Principal me = requireFacilitator();
        Optional<ScoringEngine.RoundResult> result = rounds.closeAndCompute(me.tenantId(), runId);
        return result.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    private static TenantContext.Principal requireFacilitator() {
        TenantContext.Principal me = TenantContext.require();
        if (!"FACILITATOR".equals(me.role()) && !"ADMIN".equals(me.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "facilitator or admin role required");
        }
        return me;
    }
}
