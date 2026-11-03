package com.bizsim.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Which build is actually running, without a shell.
 *
 * <p>The reliable fingerprint is the migration history, not a version string: `latest` tells you
 * whether a deploy landed (V11 means the setup workbench is live), and `migrations` tells you nothing
 * was skipped. Deliberately unauthenticated — you want to check this before you can sign in, and it
 * exposes nothing about tenants, accounts or data.
 */
@RestController
@RequestMapping("/api")
public class VersionController {

    public record VersionView(String version, long migrations, String latest, String latestDescription,
                              String latestAppliedAt, String serverTime) {
    }

    private final JdbcTemplate jdbc;
    private final String version;

    public VersionController(JdbcTemplate jdbc, @Value("${bizsim.version:dev}") String version) {
        this.jdbc = jdbc;
        this.version = version;
    }

    @GetMapping("/version")
    public VersionView version() {
        long applied = count();
        Map<String, Object> latest = latest();
        return new VersionView(
                version,
                applied,
                latest == null ? "none" : String.valueOf(latest.get("version")),
                latest == null ? "" : String.valueOf(latest.get("description")),
                latest == null ? "" : String.valueOf(latest.get("installed_on")),
                OffsetDateTime.now().toString());
    }

    private long count() {
        Long applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Long.class);
        return applied == null ? 0 : applied;
    }

    private Map<String, Object> latest() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select version, description, installed_on
                from flyway_schema_history where success
                order by installed_rank desc limit 1
                """);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
