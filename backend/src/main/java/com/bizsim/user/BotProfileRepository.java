package com.bizsim.user;

import com.bizsim.web.ProblemException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The library of reusable auto workers (D47).
 *
 * <p>A profile is a brain a builder owns: level, personality and the numeric knobs the policy reads.
 * Editing one bumps its version, because a decision records the version that produced it — changing
 * `market_1_high` next term must not rewrite what it did last term.
 *
 * <p>A person (`app_user` with role AUTO_WORKER) points at a profile; a seat (`player.bot_profile_id`)
 * is what makes a company auto-played. That separation is what lets the same profile play in five
 * classes at once while each company keeps its own identity and trace.
 */
@Repository
public class BotProfileRepository {

    public record Profile(long id, long tenantId, String key, String name, String level,
                          String personality, int version, boolean active, String paramsJson) {
    }

    private static final RowMapper<Profile> MAPPER = (rs, i) -> new Profile(
            rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("key"), rs.getString("name"),
            rs.getString("level"), rs.getString("personality"), rs.getInt("version"),
            rs.getBoolean("active"), rs.getString("params"));

    private final JdbcTemplate jdbc;

    public BotProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Profile> list(long tenantId) {
        return jdbc.query("select *, params::text as params from bot_profile where tenant_id = ? "
                + "order by level desc, personality, name", MAPPER, tenantId);
    }

    public Optional<Profile> find(long tenantId, long id) {
        return jdbc.query("select *, params::text as params from bot_profile where tenant_id = ? and id = ?",
                MAPPER, tenantId, id).stream().findFirst();
    }

    public Optional<Profile> findByKey(long tenantId, String key) {
        return jdbc.query("select *, params::text as params from bot_profile where tenant_id = ? and key = ?",
                MAPPER, tenantId, key).stream().findFirst();
    }

    /**
     * Creates the profile and returns it. The key is derived from the name so two builders naming a
     * bot the same way in the same tenant collide loudly rather than silently.
     */
    public Profile create(long tenantId, String name, String level, String personality,
                          String paramsJson, long createdBy) {
        String key = name.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (key.isEmpty()) {
            key = "bot";
        }
        if (findByKey(tenantId, key).isPresent()) {
            key = key + "-" + UUID.randomUUID().toString().substring(0, 6);
        }
        return jdbc.queryForObject("""
                insert into bot_profile (tenant_id, key, name, level, personality, params, created_by)
                values (?, ?, ?, ?, ?, ?::jsonb, ?)
                returning *, params::text as params
                """, MAPPER, tenantId, key, name, level, personality,
                paramsJson == null || paramsJson.isBlank() ? "{}" : paramsJson, createdBy);
    }

    /** Edits bump the version: history stays attached to the version that produced it. */
    public Profile update(long tenantId, long id, String name, String level, String personality,
                          String paramsJson) {
        Profile existing = find(tenantId, id).orElseThrow(
                () -> ProblemException.notFound("bot.not_found", "no such auto worker"));
        return jdbc.queryForObject("""
                update bot_profile
                set name = ?, level = ?, personality = ?, params = ?::jsonb,
                    version = version + 1, updated_at = now()
                where tenant_id = ? and id = ?
                returning *, params::text as params
                """, MAPPER, name, level, personality,
                paramsJson == null || paramsJson.isBlank() ? existing.paramsJson() : paramsJson,
                tenantId, id);
    }

    /** How many people (and seats) use this brain: a profile in use is retired, never deleted. */
    public Map<String, Integer> usage(long tenantId, long id) {
        Integer people = jdbc.queryForObject(
                "select count(*) from app_user where tenant_id = ? and bot_profile_id = ?",
                Integer.class, tenantId, id);
        Integer seats = jdbc.queryForObject(
                "select count(*) from player p join bot_profile b on b.id = p.bot_profile_id "
                        + "where b.tenant_id = ? and b.id = ?", Integer.class, tenantId, id);
        return Map.of("people", people == null ? 0 : people, "seats", seats == null ? 0 : seats);
    }

    public void deactivate(long tenantId, long id) {
        jdbc.update("update bot_profile set active = false, updated_at = now() "
                + "where tenant_id = ? and id = ?", tenantId, id);
    }
}
