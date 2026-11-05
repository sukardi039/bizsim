package com.bizsim.sim;

import com.bizsim.tenant.TenantContext;
import com.bizsim.web.ProblemException;
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
    private final DebriefExportService debrief;

    public SimulationController(RoundService rounds, DebriefExportService debrief) {
        this.rounds = rounds;
        this.debrief = debrief;
    }

    @PostMapping("/teams/{teamId}/decisions")
    public Map<String, Object> submit(@PathVariable long runId, @PathVariable long teamId,
                                      @RequestBody DecisionPayload payload) {
        TenantContext.Principal me = TenantContext.require();
        if (!"PLAYER".equals(me.role())) {
            throw ProblemException.forbidden("auth.player_only", "player role required");
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

    // ------------------------------------------------------------------ facilitator powers (D12)

    public record ExtendClockRequest(Integer minutes) {
    }

    public record CashAdjustRequest(java.math.BigDecimal delta, String reason) {
    }

    public record VoidRoundRequest(String reason) {
    }

    /** Stop the round clock: the deadline is frozen and the sweep skips the round. */
    @PostMapping("/rounds/clock/pause")
    public RoundService.ClockState pauseClock(@PathVariable long runId) {
        TenantContext.Principal me = requireFacilitator();
        return rounds.pauseClock(me.tenantId(), runId, me.userId());
    }

    @PostMapping("/rounds/clock/resume")
    public RoundService.ClockState resumeClock(@PathVariable long runId) {
        TenantContext.Principal me = requireFacilitator();
        return rounds.resumeClock(me.tenantId(), runId, me.userId());
    }

    @PostMapping("/rounds/clock/extend")
    public RoundService.ClockState extendClock(@PathVariable long runId,
                                               @RequestBody ExtendClockRequest request) {
        TenantContext.Principal me = requireFacilitator();
        int minutes = request.minutes() == null ? 0 : request.minutes();
        return rounds.extendClock(me.tenantId(), runId, me.userId(), minutes);
    }

    /** Moves one company's cash by hand; the reason is mandatory and lands in the audit trail. */
    @PostMapping("/teams/{teamId}/cash")
    public Map<String, Object> adjustCash(@PathVariable long runId, @PathVariable long teamId,
                                          @RequestBody CashAdjustRequest request) {
        TenantContext.Principal me = requireFacilitator();
        return rounds.adjustCash(me.tenantId(), runId, me.userId(), teamId,
                request.delta(), request.reason());
    }

    /** Undo the last computed round: restore every company and reopen the month. */
    @PostMapping("/rounds/void")
    public Map<String, Object> voidLastRound(@PathVariable long runId,
                                             @RequestBody(required = false) VoidRoundRequest request) {
        TenantContext.Principal me = requireFacilitator();
        return rounds.voidLastRound(me.tenantId(), runId, me.userId(),
                request == null ? null : request.reason());
    }

    /** What every company sent this round — submitted or not. Facilitator only (D12). */
    @GetMapping("/decisions")
    public Map<String, Object> submissions(@PathVariable long runId,
                                           @RequestParam(required = false) Integer round) {
        TenantContext.Principal me = requireFacilitator();
        return rounds.submissions(me.tenantId(), runId, round);
    }

    /** Everything done to this session by hand, in order. */
    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@PathVariable long runId) {
        TenantContext.Principal me = requireFacilitator();
        return rounds.audit(me.tenantId(), runId);
    }

    /**
     * The classroom debrief as a file (D13): `format=pdf` for projecting and printing, `format=xlsx`
     * for the cohort to keep. Both read the stored results, never a recomputation.
     */
    @GetMapping("/debrief/export")
    public ResponseEntity<byte[]> exportDebrief(@PathVariable long runId,
                                                @RequestParam(required = false) Integer round,
                                                @RequestParam(defaultValue = "pdf") String format) {
        TenantContext.Principal me = requireFacilitator();
        DebriefExportService.Report report = debrief.load(me.tenantId(), runId, round);
        boolean excel = "xlsx".equalsIgnoreCase(format) || "excel".equalsIgnoreCase(format);
        byte[] body = excel ? debrief.xlsx(report) : debrief.pdf(report);
        String extension = excel ? "xlsx" : "pdf";
        String mediaType = excel
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "application/pdf";
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(mediaType))
                .header("Content-Disposition", "attachment; filename=\"bizsim-debrief-run" + runId
                        + "-round" + report.round() + "." + extension + "\"")
                .body(body);
    }

    private static TenantContext.Principal requireFacilitator() {
        TenantContext.Principal me = TenantContext.require();
        if (!"FACILITATOR".equals(me.role()) && !"ADMIN".equals(me.role())) {
            throw ProblemException.forbidden("auth.facilitator_only", "facilitator or admin role required");
        }
        return me;
    }
}
