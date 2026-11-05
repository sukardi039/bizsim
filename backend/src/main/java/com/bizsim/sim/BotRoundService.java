package com.bizsim.sim;

import com.bizsim.web.ProblemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public BotRoundService(BotRepository bots, RoundService rounds, RoundRepository roundRepository,
                           ScenarioRepository scenarios, EnvironmentService environment) {
        this.bots = bots;
        this.rounds = rounds;
        this.roundRepository = roundRepository;
        this.scenarios = scenarios;
        this.environment = environment;
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
    @Transactional
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
    @Transactional
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

        String stored = roundRepository.decisionPayload(runId, roundNumber, teamId).orElse(null);
        boolean identical = stored != null
                && canonical(stored).equals(canonical(toJson(decision.payload())));
        return new Replay(roundNumber, teamId, profile.level(), profile.personality(),
                digest(context), decision.rationale(), stored, toJson(decision.payload()), identical);
    }

    public record Replay(int round, long teamId, String level, String personality,
                         String contextDigest, String rationale, String storedPayload,
                         String replayedPayload, boolean identical) {
    }

    // ------------------------------------------------------------------ one seat

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
        rounds.submit(due.tenantId(), due.userId(), due.runId(), due.teamId(), decision.payload());
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
