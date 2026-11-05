package com.bizsim.sim;

import com.bizsim.web.ProblemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The auto worker in motion (D48–D50): it submits during the round, through the same path as a
 * trainee, and every decision leaves a trace that can be replayed.
 *
 * <p>Timing is seeded from the run and the round, so a bot does not submit the instant the round
 * opens: the class sees "2 of 3 submitted" without learning what the opponent did. The deadline is
 * checked on its own, so a bot that is asked to move by hand records `MANUAL` instead of pretending
 * to be scheduled.
 *
 * <p>No privileged rescue: if the round closes before a bot has spoken, the month is computed with no
 * decision, exactly as it would be for a person. The trace marks it `NONE`, which is how a broken
 * worker becomes visible instead of quietly advantaged.
 */
@Service
public class BotRoundService {

    private static final Logger log = LoggerFactory.getLogger(BotRoundService.class);

    private final BotRepository bots;
    private final RoundService rounds;
    private final RoundRepository roundRepository;
    private final ScenarioRepository scenarios;
    private final EnvironmentService environment;
    private final ScoringEngine engine;

    public BotRoundService(BotRepository bots, RoundService rounds, RoundRepository roundRepository,
                           ScenarioRepository scenarios, EnvironmentService environment,
                           ScoringEngine engine) {
        this.bots = bots;
        this.rounds = rounds;
        this.roundRepository = roundRepository;
        this.scenarios = scenarios;
        this.environment = environment;
        this.engine = engine;
    }

    /** Every five seconds: whoever is due to move, moves. Failures are logged per seat. */
    @Scheduled(fixedDelay = 5000L, initialDelay = 20_000L)
    public void tick() {
        Map<String, Integer> minutesByTemplate = new java.util.HashMap<>();
        for (BotRepository.Due due : bots.due(null, null)) {
            try {
                int minutes = minutesByTemplate.computeIfAbsent(due.scenarioKey(),
                        key -> scenarios.load(key).params().roundMinutes());
                // The window is a share of the real round length: a template with five-minute rounds
                // must not have its opponents submitting after the round has already closed.
                if (Instant.now().isBefore(submitAt(due, minutes))) {
                    continue;
                }
                decideOne(due, "SCHEDULED");
            } catch (RuntimeException e) {
                log.warn("auto worker {} (run {}) could not decide: {}", due.companyName(),
                        due.runId(), e.getMessage());
            }
        }
    }

    /** "Ask the opponents to move now" — a facilitator's button and the tests' entry point. */
    // Same reasoning as decideBeforeClose: one seat's refusal must not poison the others' commits.
    public int decideNow(long tenantId, long runId) {
        int decided = 0;
        for (BotRepository.Due due : bots.due(tenantId, runId)) {
            try {
                decideOne(due, "MANUAL");
                decided++;
            } catch (RuntimeException e) {
                log.warn("auto worker {} (run {}) refused the manual call: {}", due.companyName(),
                        runId, e.getMessage());
            }
        }
        return decided;
    }

    /**
     * A round is about to be computed. Any opponent whose seeded moment has not arrived yet decides
     * now, as `AT_CLOSE` (D56): a facilitator computing early is the normal case in a workshop, and a
     * company that never had its turn is not "a company like any other" — it is one that was denied
     * the chance a person at the keyboard had. An opponent whose moment *did* pass and still produced
     * nothing keeps its absence visible, so a broken worker is not hidden by this.
     */
    // Deliberately NOT transactional: each submission below is its own transaction (RoundService.submit),
    // and a seat that fails must not mark a surrounding transaction rollback-only — that turned one bad
    // opponent into an opaque Internal Server Error for the whole compute (D59).
    public int decideBeforeClose(long tenantId, long runId) {
        int decided = 0;
        for (BotRepository.Due due : bots.due(tenantId, runId)) {
            try {
                int minutes = scenarios.load(due.scenarioKey()).params().roundMinutes();
                if (Instant.now().isBefore(submitAt(due, minutes))) {
                    decideOne(due, "AT_CLOSE");
                    decided++;
                }
            } catch (RuntimeException e) {
                log.warn("auto worker {} (run {}) could not decide at close: {}", due.companyName(),
                        runId, e.getMessage());
            }
        }
        return decided;
    }

    public List<BotRepository.TraceRow> trace(long tenantId, long runId) {
        return bots.trace(tenantId, runId);
    }

    /**
     * Would the same brain make the same decision again? Rebuilds the context from the snapshot taken
     * when the books opened, re-runs the policy, and compares the payloads. A difference means either
     * the brain was edited since (the version says which) or the trace is not trustworthy.
     */
    public Replay replay(long tenantId, long runId, int roundNumber, long teamId) {
        Scenario scenario = scenarios.load(scenarioKey(tenantId, runId));
        BotRepository.State state = bots.snapshot(scenario, runId, roundNumber, teamId).orElseThrow(
                () -> ProblemException.conflict("bot.no_snapshot",
                        "this round has no snapshot, so it cannot be replayed"));
        AutoWorker.Profile profile = profileOf(tenantId, runId, teamId);
        AutoWorker.Context context = context(scenario, runId, roundNumber, state);
        AutoWorker.Decision decision = AutoWorker.decide(scenario, runId, teamId, context, profile);
        // Same trimming the live path applies, or a trimmed month would replay as a mismatch.
        DecisionPayload replayed = affordable(scenario,
                environment.forRound(scenario, runId, roundNumber), state, teamId, decision.payload());

        String stored = roundRepository.decisionPayload(runId, roundNumber, teamId).orElse(null);
        boolean identical = stored != null
                && canonical(stored).equals(canonical(toJson(replayed)));
        return new Replay(roundNumber, teamId, profile.level(), profile.personality(),
                digest(context), decision.rationale(), stored, toJson(replayed), identical);
    }

    public record Replay(int round, long teamId, String level, String personality,
                         String contextDigest, String rationale, String storedPayload,
                         String replayedPayload, boolean identical) {
    }

    // ------------------------------------------------------------------ one seat

    /**
     * A plan the company can pay for (D59). The engine's own preview decides: materials, marketing, R&D,
     * capacity purchases and hiring must fit inside cash, or the submission is refused — which is how a
     * policy that plans too boldly ends up skipping months. Cuts come cheap first (marketing, then R&D,
     * then capacity, then production), so what survives is the plan that keeps the company trading.
     */
    private DecisionPayload affordable(Scenario scenario, EnvironmentService.RoundEnvironment env,
                                       BotRepository.State state, long teamId, DecisionPayload plan) {
        BigDecimal cash = state.cash() == null ? BigDecimal.ZERO : state.cash();
        DecisionPayload current = plan;
        ScoringEngine.Commitment last = null;
        for (int attempt = 0; attempt < 14; attempt++) {
            ScoringEngine.Commitment commitment = engine.preview(scenario, env,
                    new ScoringEngine.TeamInput(teamId, state.status(), cash, state.brand(),
                            state.machineWorkers(), state.staff(), state.products(), state.rnd(),
                            current));
            boolean overCash = commitment.discretionary().compareTo(cash) > 0;
            boolean overStaff = commitment.plannedWorkers().compareTo(commitment.capacityAvailable()) > 0;
            last = commitment;
            if (!overCash && !overStaff) {
                return current;
            }
            DecisionPayload next = shrink(current, overStaff);
            if (next.equals(current)) {
                break;
            }
            current = next;
        }
        // Nothing left to cut: a company that cannot pay for materials or marketing still has to send
        // *something*, or the month is skipped. It sends the only plan it can afford — no spending at
        // all, prices held, so whatever stock it has can still sell.
        List<DecisionPayload.ProductRow> idle = current.production() == null ? List.of()
                : current.production().stream().map(row ->
                        new DecisionPayload.ProductRow(row.product(), BigDecimal.ZERO)).toList();
        // Workers must fit the plant the company actually has: an idle plan that still claims more
        // workers than capacity is refused as hard as an expensive one.
        // Workers must fit both the plant and the payroll: deploying more people than are employed
        // costs a hiring fee, which is itself a discretionary cost the company cannot pay.
        int employed = state.staff() == null ? 0 : state.staff().getOrDefault("PRODUCTION", 0);
        int idleWorkers = last == null ? 0
                : Math.min(employed, last.capacityAvailable().intValue());
        DecisionPayload.ManpowerRow idleManpower =
                new DecisionPayload.ManpowerRow(Math.max(0, idleWorkers));
        return new DecisionPayload(idle, current.price(), List.of(), idleManpower, List.of(),
                List.of(), BigDecimal.ZERO, current.marketReport());
    }

    /** One notch cheaper: marketing first, then R&D, then capacity, then units. */
    private static DecisionPayload shrink(DecisionPayload plan, boolean cutOutput) {
        List<DecisionPayload.ChannelRow> marketing = plan.marketing() == null ? List.of() : plan.marketing();
        BigDecimal spend = marketing.stream().map(DecisionPayload.ChannelRow::spend)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (spend.signum() > 0) {
            return new DecisionPayload(plan.production(), plan.price(),
                    marketing.stream().map(row -> new DecisionPayload.ChannelRow(row.channel(),
                            row.spend() == null ? BigDecimal.ZERO
                                    : row.spend().multiply(new BigDecimal("0.5"))
                                    .setScale(0, java.math.RoundingMode.DOWN))).toList(),
                    plan.manpower(), plan.rnd(), plan.quality(), plan.capacityPurchase(),
                    plan.marketReport());
        }
        List<DecisionPayload.RndRow> rnd = plan.rnd() == null ? List.of() : plan.rnd();
        BigDecimal invest = rnd.stream().map(DecisionPayload.RndRow::invest)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (invest.signum() > 0) {
            return new DecisionPayload(plan.production(), plan.price(), plan.marketing(), plan.manpower(),
                    rnd.stream().map(row -> new DecisionPayload.RndRow(row.project(),
                            row.invest() == null ? BigDecimal.ZERO
                                    : row.invest().multiply(new BigDecimal("0.5"))
                                    .setScale(0, java.math.RoundingMode.DOWN))).toList(),
                    plan.quality(), plan.capacityPurchase(), plan.marketReport());
        }
        if (plan.capacityPurchase() != null && plan.capacityPurchase().signum() > 0) {
            return new DecisionPayload(plan.production(), plan.price(), plan.marketing(), plan.manpower(),
                    plan.rnd(), plan.quality(), BigDecimal.ZERO, plan.marketReport());
        }
        if (plan.production() != null && !plan.production().isEmpty()) {
            // Output is the last thing to go, and only when the workforce cannot cover it either.
            return new DecisionPayload(plan.production().stream()
                    .map(row -> new DecisionPayload.ProductRow(row.product(),
                            row.units() == null ? BigDecimal.ZERO
                                    : row.units().multiply(new BigDecimal("0.7"))
                                    .setScale(0, java.math.RoundingMode.DOWN))).toList(),
                    plan.price(), plan.marketing(), plan.manpower(), plan.rnd(), plan.quality(),
                    plan.capacityPurchase(), plan.marketReport());
        }
        return plan;
    }

    private void decideOne(BotRepository.Due due, String mode) {
        Scenario scenario = scenarios.load(due.scenarioKey());
        if (!scenario.params().autoWorkers()) {
            throw ProblemException.conflict("bot.switch_off",
                    "this template does not seat auto workers");
        }
        AutoWorker.Profile profile = profileFrom(due);
        BotRepository.State state = bots.state(scenario, due.runId(), due.roundNumber(), due.teamId());
        AutoWorker.Context context = context(scenario, due.runId(), due.roundNumber(), state);
        AutoWorker.Decision decision = AutoWorker.decide(scenario, due.runId(), due.teamId(),
                context, profile);

        // The same method a trainee's form posts to: same clock, same validator, same affordability.
        // A plan the company cannot pay for is trimmed against the engine's own preview before it is
        // submitted, so a bold month becomes a smaller one instead of a missing one.
        DecisionPayload payload = affordable(scenario,
                environment.forRound(scenario, due.runId(), due.roundNumber()), state, due.teamId(),
                decision.payload());
        rounds.submit(due.tenantId(), due.userId(), due.runId(), due.teamId(), payload);
        bots.record(due.roundId(), due.teamId(), due.profileKey(), due.profileVersion(),
                due.paramsJson(), digest(due.paramsJson()), decision.seedKey(), digest(context),
                decision.rationale(), mode);
    }

    private AutoWorker.Profile profileOf(long tenantId, long runId, long teamId) {
        for (BotRepository.Due due : bots.due(tenantId, runId)) {
            if (due.teamId() == teamId) {
                return profileFrom(due);
            }
        }
        // Already decided: read the brain from the seat itself.
        int profileId = bots.botProfileOf(runId, teamId).orElseThrow(
                () -> ProblemException.notFound("bot.not_seated", "this company is not auto-played"));
        return profileFrom(bots.profile(tenantId, profileId));
    }

    private static AutoWorker.Profile profileFrom(BotRepository.Due due) {
        return AutoWorker.withParams(
                AutoWorker.profile(due.level(), due.personality()), parse(due.paramsJson()));
    }

    private static AutoWorker.Profile profileFrom(BotProfileView view) {
        return AutoWorker.withParams(AutoWorker.profile(view.level(), view.personality()),
                parse(view.paramsJson()));
    }

    /** Minimal view so the service can read a brain without depending on the user package. */
    public record BotProfileView(String level, String personality, String paramsJson) {
    }

    private AutoWorker.Context context(Scenario scenario, long runId, int round,
                                       BotRepository.State state) {
        EnvironmentService.RoundEnvironment env = environment.forRound(scenario, runId, round);
        return new AutoWorker.Context(round, scenario.params().rounds(), state.cash(), state.brand(),
                state.machineWorkers(), state.staff(), state.products(), state.rnd(),
                state.lastLines(), env);
    }

    /** Seeded submit time: 20–70% into the round, so the class cannot read the opponent early. */
    private Instant submitAt(BotRepository.Due due, int roundMinutes) {
        if (due.openedAt() == null) {
            return Instant.now();
        }
        int minutes = roundMinutes <= 0 ? 20 : roundMinutes;
        BigDecimal fraction = new BigDecimal("0.2").add(Seed.uniform(
                due.runId() + ":bot-at:" + due.roundNumber() + ":" + due.teamId())
                .multiply(new BigDecimal("0.5")));
        long seconds = Math.max(5L, (long) (minutes * 60 * fraction.doubleValue()));
        return due.openedAt().plus(Duration.ofSeconds(seconds));
    }

    private String scenarioKey(long tenantId, long runId) {
        return roundRepository.scenarioKeyOfRun(tenantId, runId).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
    }

    // ------------------------------------------------------------------ misc

    private static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialise the decision", e);
        }
    }

    /** JSON with a stable key order, so "same decision" is a string comparison that means something. */
    private static String canonical(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            return canonicalNode(node);
        } catch (Exception e) {
            return json;
        }
    }

    private static String canonicalNode(com.fasterxml.jackson.databind.JsonNode node) {
        StringBuilder out = new StringBuilder();
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append('"').append(names.get(i)).append("\":");
                out.append(canonicalNode(node.get(names.get(i))));
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(canonicalNode(node.get(i)));
            }
            out.append(']');
        } else if (node.isNumber()) {
            out.append(node.decimalValue().stripTrailingZeros().toPlainString());
        } else {
            out.append(node.toString());
        }
        return out.toString();
    }

    private static String digest(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String text = value instanceof String s ? s : toJson(value);
            return HexFormat.of().formatHex(digest.digest(canonical(text).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot digest", e);
        }
    }
}
