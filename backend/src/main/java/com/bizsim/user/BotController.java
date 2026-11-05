package com.bizsim.user;

import com.bizsim.web.ProblemException;
import com.bizsim.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The auto-worker library (D47): reusable opponents a builder creates once and seats in packages.
 *
 * <p>Creating one does two things in one transaction — a profile (the brain) and a person with role
 * AUTO_WORKER (the identity that signs decisions). The name carries the level as a suffix
 * (`Market 1_high`) because that is what a trainer reads on a scoreboard, but the suffix is
 * <em>generated</em>: the stored level is the truth, so renaming cannot swap the brain.
 *
 * <p>Only a builder or an admin may touch this: an opponent is preparation, and a facilitator must
 * never be able to add one to a class already in progress (D24/D48).
 */
@RestController
@RequestMapping("/api/bots")
public class BotController {

    private static final List<String> LEVELS = List.of("LOW", "MID", "HIGH");
    private static final List<String> PERSONALITIES = List.of("CAUTIOUS", "BALANCED", "AGGRESSIVE");

    public record BotRequest(@NotBlank String name, String level, String personality,
                             Map<String, Object> params) {
    }

    public record BotView(long id, String key, String name, String level, String personality,
                          int version, boolean active, Long userId) {
    }

    private final BotProfileRepository bots;
    private final UserRepository users;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public BotController(BotProfileRepository bots, UserRepository users) {
        this.bots = bots;
        this.users = users;
    }

    @GetMapping
    public List<BotView> list() {
        TenantContext.Principal me = TenantContext.require();
        return bots.list(me.tenantId()).stream()
                .map(profile -> new BotView(profile.id(), profile.key(), profile.name(), profile.level(),
                        profile.personality(), profile.version(), profile.active(),
                        users.botPersonFor(me.tenantId(), profile.id()).orElse(null)))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BotView create(@Valid @RequestBody BotRequest request) {
        TenantContext.Principal me = requireBuilder();
        String level = levelOf(request);
        String personality = personalityOf(request);
        String name = displayName(request.name(), level);
        BotProfileRepository.Profile profile = bots.create(me.tenantId(), name, level, personality,
                paramsJson(request.params()), me.userId());
        long userId = createBotPerson(me.tenantId(), profile);
        return new BotView(profile.id(), profile.key(), profile.name(), profile.level(),
                profile.personality(), profile.version(), profile.active(), userId);
    }

    @PutMapping("/{id}")
    public BotView update(@PathVariable long id, @Valid @RequestBody BotRequest request) {
        TenantContext.Principal me = requireBuilder();
        String level = levelOf(request);
        String personality = personalityOf(request);
        String name = displayName(request.name(), level);
        BotProfileRepository.Profile profile = bots.update(me.tenantId(), id, name, level, personality,
                paramsJson(request.params()));
        users.renameBotPerson(me.tenantId(), id, name);
        return new BotView(profile.id(), profile.key(), profile.name(), profile.level(),
                profile.personality(), profile.version(), profile.active(),
                users.botPersonFor(me.tenantId(), id).orElse(null));
    }

    /** A brain a class has played is retired, never deleted: decisions point at its versions. */
    @PostMapping("/{id}/retire")
    public Map<String, Object> retire(@PathVariable long id) {
        TenantContext.Principal me = requireBuilder();
        Map<String, Integer> usage = bots.usage(me.tenantId(), id);
        if (usage.get("seats") > 0) {
            throw ProblemException.conflict("bot.in_use",
                    "this auto worker has played in a session; retire it instead of deleting it");
        }
        bots.deactivate(me.tenantId(), id);
        return Map.of("id", id, "active", false);
    }

    // ------------------------------------------------------------------ helpers

    private long createBotPerson(long tenantId, BotProfileRepository.Profile profile) {
        String email = profile.key() + "@bots.invalid";
        if (users.findByEmailInTenant(tenantId, email).isPresent()) {
            email = profile.key() + "-" + profile.id() + "@bots.invalid";
        }
        // Nobody is told this password, and login refuses the role anyway.
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        return users.insertBot(tenantId, email, profile.name(),
                encoder.encode(HexFormat.of().formatHex(secret)), profile.id()).id();
    }

    private TenantContext.Principal requireBuilder() {
        TenantContext.Principal me = TenantContext.require();
        if (!List.of("BUILDER", "ADMIN").contains(me.role())) {
            throw ProblemException.forbidden("auth.builder_only",
                    "only a builder or an admin may manage auto workers");
        }
        return me;
    }

    /** The suffix wins if the trainer typed one; otherwise the level field decides. */
    private static String levelOf(BotRequest request) {
        String fromName = suffix(request.name());
        String level = fromName != null ? fromName
                : request.level() == null ? "MID" : request.level().trim().toUpperCase(java.util.Locale.ROOT);
        if (!LEVELS.contains(level)) {
            throw ProblemException.badRequest("bot.level_invalid", "level must be LOW, MID or HIGH");
        }
        return level;
    }

    private static String personalityOf(BotRequest request) {
        String personality = request.personality() == null ? "BALANCED"
                : request.personality().trim().toUpperCase(java.util.Locale.ROOT);
        if (!PERSONALITIES.contains(personality)) {
            throw ProblemException.badRequest("bot.personality_invalid",
                    "personality must be CAUTIOUS, BALANCED or AGGRESSIVE");
        }
        return personality;
    }

    /** Stored name always ends with `_low|_mid|_high`; a stray suffix in the input is replaced. */
    private static String displayName(String raw, String level) {
        String base = raw.trim();
        String found = suffix(base);
        if (found != null) {
            base = base.substring(0, base.length() - found.length() - 1).trim();
        }
        if (base.isEmpty()) {
            base = "Market";
        }
        return base + "_" + level.toLowerCase(java.util.Locale.ROOT);
    }

    private static String suffix(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.trim().toLowerCase(java.util.Locale.ROOT);
        for (String level : List.of("low", "mid", "high")) {
            if (lower.endsWith("_" + level)) {
                return level.toUpperCase(java.util.Locale.ROOT);
            }
        }
        return null;
    }

    private static String paramsJson(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "{}";
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params);
        } catch (Exception e) {
            throw ProblemException.badRequest("bot.params_invalid", "params must be a JSON object");
        }
    }
}
