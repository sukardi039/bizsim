package com.bizsim.sim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * D102: a shared map is a **link**, not an account.
 *
 * <p>What a facilitator sends to a class is a URL. It has to work without a login, which means the page
 * cannot read the database itself — so the figures are frozen when the link is made. Three properties
 * follow, and all three are deliberate:
 *
 * <ul>
 *   <li><b>A snapshot, not a live view.</b> The link shows the round it was created for. Recomputing a
 *       round does not silently change what the class was shown.</li>
 *   <li><b>Unguessable and revocable.</b> The token is 32 random bytes; the row can be withdrawn, and a
 *       withdrawn or expired link answers 404, not a login prompt.</li>
 *   <li><b>Nothing secret in it.</b> The payload is the debrief's own figures — the same numbers the class
 *       already sees on the standings — plus the company names. No cost breakdown, no decisions, no
 *       account data.</li>
 * </ul>
 */
@Component
public class MapShareRepository {

    /** How long a shared link lives unless it is withdrawn first. */
    static final int EXPIRES_DAYS = 90;

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public MapShareRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Share(long id, String token, long tenantId, long runId, String kind, Integer round,
                        String title, String html, Instant createdAt, Instant expiresAt, Instant revokedAt) {

        public boolean live(Instant now) {
            return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
        }
    }

    /** The token is the only secret; it is stored hashed so a database read cannot mint links. */
    public Share create(long tenantId, long runId, String kind, Integer round, String title, String html) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        String hash = hash(token);
        Instant expires = Instant.now().plusSeconds(EXPIRES_DAYS * 24L * 3600L);
        Long id = jdbc.queryForObject("""
                insert into map_share (token_hash, tenant_id, run_id, kind, round, title, html, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, hash, tenantId, runId, kind, round, title, html,
                java.sql.Timestamp.from(expires));
        return find(token).orElseThrow();
    }

    public java.util.Optional<Share> find(String token) {
        if (token == null || token.length() < 32) {
            return java.util.Optional.empty();
        }
        return jdbc.query("""
                select id, tenant_id, run_id, kind, round, title, html, created_at, expires_at, revoked_at
                from map_share where token_hash = ?
                """, (rs, row) -> new Share(
                        rs.getLong("id"), token, rs.getLong("tenant_id"), rs.getLong("run_id"),
                        rs.getString("kind"), rs.getObject("round", Integer.class), rs.getString("title"),
                        rs.getString("html"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()),
                hash(token)).stream().findFirst();
    }

    /** Withdrawing is permanent and keeps the row: what the class saw is part of the session's history. */
    public boolean revoke(long tenantId, long id) {
        return jdbc.update("""
                update map_share set revoked_at = now()
                where id = ? and tenant_id = ? and revoked_at is null
                """, id, tenantId) > 0;
    }

    public java.util.List<Share> list(long tenantId, long runId) {
        return jdbc.query("""
                select id, tenant_id, run_id, kind, round, title, '', created_at, expires_at, revoked_at
                from map_share where tenant_id = ? and run_id = ? order by created_at desc
                """, (rs, row) -> new Share(
                        rs.getLong("id"), null, rs.getLong("tenant_id"), rs.getLong("run_id"),
                        rs.getString("kind"), rs.getObject("round", Integer.class), rs.getString("title"),
                        null,
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()),
                tenantId, runId);
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
