package com.bizsim.run;

import com.bizsim.web.ProblemException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Simulation packages: the prepared artefact a facilitator executes (V12).
 *
 * <p>Immutable by construction: there is no update method, only publish and retire. A package names
 * the template *version* it was built on, so editing the template afterwards changes nothing about a
 * package already handed to a class.
 */
@Repository
public class PackageRepository {

    public record PackageRow(long id, String name, String templateKey, int templateVersion,
                             List<Long> companyProfileIds, String status, String publishedAt) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PackageRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public long insert(long tenantId, String name, String templateKey, int templateVersion,
                       List<Long> companyProfileIds, long publishedBy) {
        return jdbc.queryForObject("""
                insert into simulation_package
                    (tenant_id, name, template_key, template_version, company_profile_ids, published_by)
                values (?, ?, ?, ?, ?::jsonb, ?) returning id
                """, Long.class, tenantId, name, templateKey, templateVersion,
                writeIds(companyProfileIds), publishedBy);
    }

    public List<PackageRow> list(long tenantId) {
        return jdbc.query("""
                select id, name, template_key, template_version, company_profile_ids::text, status,
                       to_char(published_at, 'YYYY-MM-DD HH24:MI') as published_at
                from simulation_package where tenant_id = ? order by published_at desc, id desc
                """, (rs, i) -> new PackageRow(rs.getLong("id"), rs.getString("name"),
                rs.getString("template_key"), rs.getInt("template_version"),
                readIds(rs.getString("company_profile_ids")), rs.getString("status"),
                rs.getString("published_at")), tenantId);
    }

    public Optional<PackageRow> find(long tenantId, long id) {
        return list(tenantId).stream().filter(row -> row.id() == id).findFirst();
    }

    /** A package is retired, never deleted: the sessions it ran stay attached to its name. */
    public boolean retire(long tenantId, long id) {
        return jdbc.update("""
                update simulation_package set status = 'RETIRED'
                where id = ? and tenant_id = ? and status = 'PUBLISHED'
                """, id, tenantId) > 0;
    }

    /** The template version a session must be played on: the one frozen into the package. */
    public int templateVersionOf(long tenantId, long id) {
        return find(tenantId, id).orElseThrow(
                () -> ProblemException.notFound("package.not_found", "no such package")).templateVersion();
    }

    private List<Long> readIds(String raw) {
        try {
            return json.readValue(raw == null ? "[]" : raw, new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("unreadable package profile ids", e);
        }
    }

    private String writeIds(List<Long> ids) {
        try {
            return json.writeValueAsString(ids == null ? List.of() : ids);
        } catch (Exception e) {
            throw new IllegalStateException("cannot write package profile ids", e);
        }
    }
}
