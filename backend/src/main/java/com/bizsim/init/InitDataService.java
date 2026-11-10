package com.bizsim.init;

import com.bizsim.run.CompanyProfileRepository;
import com.bizsim.run.PackageRepository;
import com.bizsim.run.PackageService;
import com.bizsim.run.RunRepository;
import com.bizsim.run.RunService;
import com.bizsim.sim.AutoWorker;
import com.bizsim.sim.BotRoundService;
import com.bizsim.sim.DecisionPayload;
import com.bizsim.sim.RoundService;
import com.bizsim.sim.ScenarioRepository;
import com.bizsim.user.UserRepository;
import com.bizsim.web.ProblemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Init data: a tenant a class can actually be run in, created from an empty database.
 *
 * <p><strong>What an empty database already has.</strong> Flyway seeds the whole simulation content —
 * the shipped {@code standard-v1} template, its three products, three channels, five R&amp;D projects
 * (plus the Chinese catalogue), the eight-round environment, the event deck and one global company
 * profile. What migrations cannot create is <em>people and a class</em>, because that is tenant data.
 * This service creates exactly that and nothing else.
 *
 * <p><strong>Why it calls the services instead of writing SQL.</strong> A class is not a bag of rows.
 * The package has to freeze its template (D33/D54), record its parts (D62), hold its seats (D27) and
 * pin its language (D66); the run has to freeze its own economy and event deck (D18) and copy each
 * company's product line from its profile. Raw inserts would produce rows the console cannot render
 * and a run the engine cannot compute. Every step here is the same call the UI makes.
 *
 * <p><strong>Determinism.</strong> The months are played by the shipped policy
 * ({@link AutoWorker}, MID/BALANCED quality, varied per company) and every submission goes through
 * {@link RoundService#submit}, so the validator and the affordability preview apply exactly as they
 * would to a trainee. The engine is deterministic, so a given run always computes the same numbers;
 * two separate initialisations differ only because their run ids differ.
 *
 * <p><strong>Idempotency.</strong> Initialisation happens once per tenant. A second call on a tenant
 * that already has a package is refused rather than merged — silently appending a second class to a
 * half-built tenant is the failure mode worth avoiding. Accounts that already exist are reused, never
 * rewritten, so running this against a tenant someone has signed into cannot reset a password.
 */
@Service
public class InitDataService {

    private static final Logger log = LoggerFactory.getLogger(InitDataService.class);

    /** The password used when the caller does not supply one; documented, and only for a demo. */
    public static final String DEFAULT_PASSWORD = "demo" + "1234";

    /** The shipped template every tenant starts from. */
    private static final String SHIPPED_TEMPLATE = "standard-v1";

    /**
     * Who plays the seeded companies, position by position. Deliberately a spread rather than five
     * copies of one brain: a demo where every opponent behaves identically teaches nothing.
     */
    private static final List<String[]> PLAYERS_STYLES = List.of(
            new String[]{"HIGH", "BALANCED"},
            new String[]{"MID", "AGGRESSIVE"},
            new String[]{"MID", "CAUTIOUS"},
            new String[]{"LOW", "BALANCED"},
            new String[]{"HIGH", "AGGRESSIVE"},
            new String[]{"LOW", "CAUTIOUS"});

    public record Params(int companies, int trainees, int rounds, Integer cycles, String locale,
                         String password, String tenantName, String packageName, String runName) {

        public static Params defaults() {
            return new Params(4, 4, 8, null, "en", DEFAULT_PASSWORD, null, null, null);
        }
    }

    /** What was created, including the credentials, so a caller can print a sign-in card once. */
    public record Summary(long tenantId, String tenantCode, String locale, String password,
                          long adminId, long builderId, long facilitatorId, List<String> playerEmails,
                          String templateKey, List<Long> companyProfileIds, long packageId,
                          String packageName, long runId, String runStatus, int roundsComputed,
                          int rounds, int companies) {
    }

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final ScenarioRepository scenarios;
    private final CompanyProfileRepository profiles;
    private final PackageService packageService;
    private final PackageRepository packages;
    private final RunService runs;
    private final RoundService rounds;
    private final BotRoundService bots;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public InitDataService(JdbcTemplate jdbc, UserRepository users, ScenarioRepository scenarios,
                           CompanyProfileRepository profiles, PackageService packageService,
                           PackageRepository packages, RunService runs, RoundService rounds,
                           BotRoundService bots) {
        this.jdbc = jdbc;
        this.users = users;
        this.scenarios = scenarios;
        this.profiles = profiles;
        this.packageService = packageService;
        this.packages = packages;
        this.runs = runs;
        this.rounds = rounds;
        this.bots = bots;
    }

    /**
     * Creates the tenant, its staff and trainees, a template copy, one company profile per company,
     * a published package, a started session, and — when {@code rounds > 0} — plays that session to
     * a computed result.
     *
     * <p>The whole thing is one transaction: a failure halfway leaves an empty tenant, never a
     * half-published class that a facilitator could start by accident.
     */
    @Transactional
    public Summary init(String tenantCode, Params params) {
        String code = normaliseCode(tenantCode);
        Params p = params == null ? Params.defaults() : params;
        int companies = clamp(p.companies(), 1, 6);
        int trainees = clamp(p.trainees(), 1, companies);
        int requestedRounds = clamp(p.rounds(), 0, 12);
        String locale = p.locale() == null || p.locale().isBlank() ? "en" : p.locale().trim().toLowerCase(Locale.ROOT);
        if (!List.of("en", "zh").contains(locale)) {
            throw ProblemException.badRequest("init.locale_invalid",
                    "the class language must be en or zh", "field", "locale");
        }
        String password = p.password() == null || p.password().isBlank() ? DEFAULT_PASSWORD : p.password();
        if (password.length() < 8) {
            throw ProblemException.badRequest("init.password_too_short",
                    "the seed password must be at least 8 characters", "min", 8);
        }

        long tenantId = ensureTenant(code, p.tenantName(), locale);
        if (packages.countForTenant(tenantId) > 0) {
            throw ProblemException.conflict("init.already_present",
                    "tenant '" + code + "' already has a package; init data runs once per tenant",
                    "tenant", code);
        }

        // ---------------------------------------------------------------- people
        long adminId = ensureUser(tenantId, code, "admin", "Administrator", "ADMIN", password);
        long builderId = ensureUser(tenantId, code, "builder", "Builder", "BUILDER", password);
        long facilitatorId = ensureUser(tenantId, code, "trainer", "Facilitator", "FACILITATOR", password);

        List<Long> playerIds = new ArrayList<>();
        List<String> playerEmails = new ArrayList<>();
        for (int i = 1; i <= trainees; i++) {
            String email = "player" + i + "@" + code + ".test";
            playerIds.add(ensureUser(tenantId, code, "player" + i, "Player " + i, "PLAYER", password));
            playerEmails.add(email);
        }

        // ---------------------------------------------------------------- template + companies
        String templateKey = ensureDraftTemplate(tenantId, code);
        if (requestedRounds > 0) {
            shapeSession(templateKey, requestedRounds, p.cycles());
        }
        List<Long> profileIds = ensureCompanyProfiles(tenantId, companies);

        // ---------------------------------------------------------------- package
        String packageName = blankTo(p.packageName(), "Demo programme " + code);
        PackageRepository.PackageRow pkg = packageService.publish(tenantId, code, builderId,
                new PackageService.PublishRequest(packageName, templateKey, profileIds, playerIds,
                        locale, null));

        // ---------------------------------------------------------------- session
        String runName = blankTo(p.runName(), "Demo class " + code);
        // Length comes from the template (D62), which shapeSession() has just set; the request-level
        // overrides are deliberately not used here, because a package session ignores them.
        RunService.RunView run = runs.startFromPackage(tenantId, facilitatorId, pkg.id(), runName,
                playerIds, null, null, 1);
        int plannedRounds = run.rounds();
        int computed = 0;
        if (requestedRounds > 0) {
            computed = play(tenantId, run.id(), plannedRounds);
        }

        RunService.RunView after = runs.get(tenantId, run.id());
        log.info("init data: tenant '{}' — {} companies, {} trainees, package {} ({} part(s)), "
                        + "run {} {} ({}/{} rounds computed), locale {}",
                code, companies, trainees, pkg.id(), pkg.parts().size(), after.id(), after.status(),
                computed, plannedRounds, locale);

        return new Summary(tenantId, code, locale, password, adminId, builderId, facilitatorId,
                playerEmails, templateKey, profileIds, pkg.id(), packageName, after.id(),
                after.status(), computed, plannedRounds, companies);
    }

    /**
     * Plays the session round by round: open, every company submits a plan the engine says it can
     * afford, compute. The last compute ends the run (D12), so a completed init leaves a FINISHED
     * session whose debrief and standings are real numbers.
     */
    private int play(long tenantId, long runId, int plannedRounds) {
        int computed = 0;
        for (int round = 1; round <= plannedRounds; round++) {
            runs.openNextRound(tenantId, runId);
            RunService.RunView view = runs.get(tenantId, runId);
            int index = 0;
            for (RunRepository.TeamRow team : view.teams()) {
                long owner = ownerOf(view, team.id());
                String[] style = PLAYERS_STYLES.get(index % PLAYERS_STYLES.size());
                DecisionPayload plan = bots.planFor(tenantId, runId, team.id(), round,
                        AutoWorker.profile(style[0], style[1]));
                rounds.submit(tenantId, owner, runId, team.id(), plan);
                index++;
            }
            if (rounds.closeAndCompute(tenantId, runId).isEmpty()) {
                break;
            }
            computed++;
        }
        return computed;
    }

    private static long ownerOf(RunService.RunView view, long teamId) {
        return view.players().stream()
                .filter(player -> player.teamId() == teamId && "OWNER".equals(player.roleKey()))
                .map(RunRepository.PlayerRow::userId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no seated owner for company " + teamId + " in run " + view.id()));
    }

    // ------------------------------------------------------------------ pieces

    private long ensureTenant(String code, String name, String locale) {
        Long existing = jdbc.query("select id from tenant where code = ?",
                        (rs, i) -> rs.getLong(1), code)
                .stream().findFirst().orElse(null);
        if (existing != null) {
            return existing;
        }
        return jdbc.queryForObject("""
                insert into tenant (code, name, locale) values (?, ?, ?) returning id
                """, Long.class, code, blankTo(name, code), locale);
    }

    private long ensureUser(long tenantId, String tenantCode, String local, String displayName,
                            String role, String password) {
        String email = local + "@" + tenantCode + ".test";
        return users.findByEmailInTenant(tenantId, email)
                .map(UserRepository.AppUser::id)
                .orElseGet(() -> users.insert(tenantId, email, displayName,
                        encoder.encode(password), role).id());
    }

    /**
     * A tenant-owned DRAFT copy of the shipped template. The copy is what a builder edits; a demo
     * without one shows an empty library, which is exactly the "seeded but nothing to look at" state
     * this service exists to end.
     */
    private String ensureDraftTemplate(long tenantId, String tenantCode) {
        String existing = jdbc.query("""
                        select key from scenario
                        where tenant_id = ? and status = 'DRAFT' and based_on = ?
                        order by key limit 1
                        """, (rs, i) -> rs.getString(1), tenantId, SHIPPED_TEMPLATE)
                .stream().findFirst().orElse(null);
        if (existing != null) {
            return existing;
        }
        return scenarios.copy(SHIPPED_TEMPLATE, tenantId, tenantCode, tenantCode + " template");
    }

    /**
     * How long the seeded session runs.
     *
     * <p>A session started from a package takes its length from the part's template, never from the
     * request (D62): the builder defines the simulation, the facilitator only seats people. So the
     * only honest way for init data to choose 6 rounds instead of the shipped 8 is to set it on the
     * tenant's own template copy — which is exactly what a builder would do in the editor.
     *
     * <p>{@code cycles × cycleLength} is the number of months a run gets ({@code createRun}), and
     * {@code rounds} is what the engine and the opponent policy pace against, so all three move
     * together. When the requested cycle count does not divide the rounds, cycles give way: the round
     * count is the promise, the cycle shape is the preference.
     */
    private void shapeSession(String templateKey, int requestedRounds, Integer requestedCycles) {
        int cycles = requestedCycles != null && requestedCycles > 0
                ? requestedCycles : Math.max(1, Math.min(2, requestedRounds / 2));
        int cycleLength = Math.max(1, requestedRounds / cycles);
        if (cycles * cycleLength != requestedRounds) {
            cycles = requestedRounds;
            cycleLength = 1;
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("rounds", requestedRounds);
        settings.put("cycles", cycles);
        settings.put("cycleLength", cycleLength);
        scenarios.saveSettings(templateKey, settings);
    }

    /**
     * One company profile per company, carrying the template's full product line. Profiles are what a
     * package seats, so a company without one cannot appear in a class at all.
     */
    private List<Long> ensureCompanyProfiles(long tenantId, int companies) {
        List<CompanyProfileRepository.ProfileRow> visible = profiles.listVisible(tenantId);
        long shippedId = visible.stream()
                .filter(row -> row.tenantId() == null && "Standard manufacturer".equals(row.name()))
                .map(CompanyProfileRepository.ProfileRow::id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "the shipped company profile is missing: migrations have not run"));
        Map<String, Long> own = new LinkedHashMap<>();
        for (CompanyProfileRepository.ProfileRow row : visible) {
            if (row.tenantId() != null) {
                own.put(row.name(), row.id());
            }
        }

        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= companies; i++) {
            String name = "Company " + i;
            Long id = own.get(name);
            if (id == null) {
                id = profiles.insert(tenantId, name, name + " Ltd", List.of(),
                        "Seeded by init data from the shipped profile", shippedId);
            }
            ids.add(id);
        }
        return ids;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String normaliseCode(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            throw ProblemException.badRequest("init.tenant_required",
                    "init data needs a tenant code", "field", "tenant");
        }
        String code = tenantCode.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        if (code.length() < 2 || code.length() > 40) {
            throw ProblemException.badRequest("init.tenant_invalid",
                    "the tenant code must be 2-40 characters of a-z, 0-9 or -", "field", "tenant");
        }
        return code;
    }
}
