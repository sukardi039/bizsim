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
    private final BotRoundService bots;
    private final EvaluationReportService evaluations;

    public SimulationController(RoundService rounds, DebriefExportService debrief,
                                BotRoundService bots, EvaluationReportService evaluations) {
        this.rounds = rounds;
        this.debrief = debrief;
        this.bots = bots;
        this.evaluations = evaluations;
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

    /**
     * The standings a trainee may read (D51): rank, company, this round's profit. Anything wider —
     * share, cash, stock, another company's statement — stays on the facilitator's screen.
     */
    @GetMapping("/standings")
    public List<Map<String, Object>> standings(@PathVariable long runId,
                                               @RequestParam(required = false) Integer round) {
        TenantContext.Principal me = TenantContext.require();
        return rounds.standings(me.tenantId(), me.userId(), me.role(), runId, round);
    }

    /**
     * D61: the suggested plan for a trainee's opening rounds, as a draft to edit. Empty body when the
     * guide is not on offer this round, so the console simply leaves the form blank.
     */
    @GetMapping("/teams/{teamId}/suggestion")
    public Map<String, Object> suggestion(@PathVariable long runId, @PathVariable long teamId) {
        TenantContext.Principal me = TenantContext.require();
        return bots.suggest(me.tenantId(), me.userId(), runId, teamId)
                .<Map<String, Object>>map(payload -> Map.of("guided", true, "payload", payload))
                .orElseGet(() -> Map.of("guided", false));
    }

    /**
     * D58: put the session back to its pre-start condition — same companies, same seats, month 0, no
     * rounds. The confirm lives in the console; the API does what it says.
     */
    @PostMapping("/reset")
    public Map<String, Object> reset(@PathVariable long runId,
                                     @RequestBody(required = false) Map<String, Object> body) {
        TenantContext.Principal me = requireFacilitator();
        String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return rounds.resetRun(me.tenantId(), runId, me.userId(), reason);
    }

    /**
     * D52: show the class table to trainees, or hide it again. Audited as a session control.
     */
    @PostMapping("/standings")
    public Map<String, Object> revealStandings(@PathVariable long runId,
                                               @RequestBody(required = false) Map<String, Object> body) {
        TenantContext.Principal me = requireFacilitator();
        boolean visible = body == null || !Boolean.FALSE.equals(body.get("visible"));
        return Map.of("visible", rounds.setStandingsVisible(me.tenantId(), me.userId(), runId, visible));
    }

    /** Facilitator power "close a round" without opening the next one (D12). */
    @PostMapping("/rounds/close")
    public ResponseEntity<ScoringEngine.RoundResult> close(@PathVariable long runId) {
        TenantContext.Principal me = requireFacilitator();
        // D56: an opponent that has not had its moment yet plays now, rather than losing the month.
        bots.decideBeforeClose(me.tenantId(), runId);
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
     * The debrief as a file: every computed round, each with its submission, its results and its cost
     * lines (D13/D43). Formats: `pdf` for projecting and printing, `xlsx` for the cohort to keep.
     *
     * <p>Staff get the whole class. A trainee gets **their own company only**, without market share or
     * any competitor line (D21); asking for someone else's company is not expressible here, because
     * the team comes from their seat, not from the request.
     */
    @GetMapping("/debrief/export")
    public ResponseEntity<byte[]> exportDebrief(@PathVariable long runId,
                                                @RequestParam(required = false) Integer round,
                                                @RequestParam(defaultValue = "pdf") String format,
                                                @RequestParam(required = false) String locale) {
        TenantContext.Principal me = TenantContext.require();
        boolean staff = "FACILITATOR".equals(me.role()) || "ADMIN".equals(me.role());
        Long teamFilter = null;
        if (!staff) {
            if (!"PLAYER".equals(me.role())) {
                throw ProblemException.forbidden("auth.staff_only", "staff or trainee role required");
            }
            teamFilter = rounds.teamOfPlayer(me.userId(), runId).orElseThrow(
                    () -> ProblemException.forbidden("team.not_yours",
                            "you are not seated in this session"));
        }
        DebriefExportService.Report report = debrief.load(me.tenantId(), runId, round, teamFilter);
        // The document speaks the reader's language (D44). The client sends the locale it is showing;
        // anything else falls back to English rather than failing a download.
        DebriefExportService.Labels labels = DebriefExportService.Labels.of(locale);
        boolean excel = "xlsx".equalsIgnoreCase(format) || "excel".equalsIgnoreCase(format);
        byte[] body = excel ? debrief.xlsx(report, labels) : debrief.pdf(report, labels);
        String extension = excel ? "xlsx" : "pdf";
        String mediaType = excel
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "application/pdf";
        String name = staff ? "bizsim-debrief-run" : "bizsim-results-run";
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(mediaType))
                .header("Content-Disposition", "attachment; filename=\"" + name + runId + "."
                        + extension + "\"")
                .body(body);
    }

    /**
     * A trainee's own development report for the term (D76): what the numbers say about how they ran
     * the company, in the class's language.
     *
     * <p>Access follows the debrief: a trainee may ask only for their own company (the team comes from
     * their seat, and asking for another one is refused, not silently redirected); staff may ask for
     * any company in the session. The document is produced from the same statement rows the console
     * reads, so it cannot disagree with what the trainee sees.
     */
    @GetMapping("/teams/{teamId}/evaluation")
    public ResponseEntity<byte[]> evaluation(@PathVariable long runId, @PathVariable long teamId,
                                             @RequestParam(required = false) String locale) {
        TenantContext.Principal me = TenantContext.require();
        boolean staff = "FACILITATOR".equals(me.role()) || "ADMIN".equals(me.role());
        if (!staff) {
            if (!"PLAYER".equals(me.role())) {
                throw ProblemException.forbidden("auth.staff_only", "staff or trainee role required");
            }
            long own = rounds.teamOfPlayer(me.userId(), runId).orElseThrow(
                    () -> ProblemException.forbidden("team.not_yours",
                            "you are not seated in this session"));
            if (own != teamId) {
                throw ProblemException.forbidden("team.not_yours",
                        "this report belongs to another company");
            }
        }
        EvaluationReportService.Evaluation evaluation = evaluations.evaluate(me.tenantId(), runId, teamId);
        // The class language decides the document (D66): the package pins it and run.lang carries it,
        // so the server never depends on the client for it. A caller may still ask for one explicitly.
        String lang = locale == null || locale.isBlank() ? evaluation.term().lang() : locale;
        byte[] body = evaluations.pdf(evaluation, EvaluationReportService.Labels.of(lang));
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType("application/pdf"))
                .header("Content-Disposition", "attachment; filename=\"bizsim-development-report-run"
                        + runId + "-team" + teamId + ".pdf\"")
                .body(body);
    }

    // ------------------------------------------------------------------ auto workers (D47–D50)

    /**
     * What the opponents did, round by round: profile version, mode, and the one-line reason. Staff
     * only — an opponent's reasoning is class material, and a trainee sees nothing of another company.
     */
    @GetMapping("/bots")
    public List<BotRepository.TraceRow> botTrace(@PathVariable long runId) {
        TenantContext.Principal me = requireStaff();
        return bots.trace(me.tenantId(), runId);
    }

    /** Ask every auto worker in this run to decide now, rather than waiting for its seeded moment. */
    @PostMapping("/bots/decide")
    public Map<String, Object> askBots(@PathVariable long runId) {
        TenantContext.Principal me = requireFacilitator();
        int decided = bots.decideNow(me.tenantId(), runId);
        return Map.of("decided", decided);
    }

    /**
     * Would the same brain make the same decision again? Rebuilds the context from the round's
     * snapshot and re-runs the policy; `identical=false` means the brain changed since, or the trace
     * cannot be trusted — either way the facilitator should know before the class asks.
     */
    @PostMapping("/bots/{teamId}/replay")
    public BotRoundService.Replay replayBot(@PathVariable long runId, @PathVariable long teamId,
                                            @RequestParam int round) {
        TenantContext.Principal me = requireStaff();
        return bots.replay(me.tenantId(), runId, round, teamId);
    }

    private static TenantContext.Principal requireStaff() {
        TenantContext.Principal me = TenantContext.require();
        if (!List.of("FACILITATOR", "ADMIN", "BUILDER").contains(me.role())) {
            throw ProblemException.forbidden("auth.staff_only", "staff only");
        }
        return me;
    }

    private static TenantContext.Principal requireFacilitator() {
        TenantContext.Principal me = TenantContext.require();
        if (!"FACILITATOR".equals(me.role()) && !"ADMIN".equals(me.role())) {
            throw ProblemException.forbidden("auth.facilitator_only", "facilitator or admin role required");
        }
        return me;
    }
}
