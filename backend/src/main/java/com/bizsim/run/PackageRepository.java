package com.bizsim.run;

import com.bizsim.web.ProblemException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    /** One seat in the prepared class: which company position, and who sits there. */
    public record Seat(int position, long userId, String displayName, String email) {
    }

    /** One simulation of a programme (D62): its own frozen template, its own rounds. */
    public record PartRow(int position, String name, String templateKey, String sourceTemplateKey,
                          int templateVersion) {
    }

    /**
     * {@code startedParts} are the parts that already have a session of this package, so the console
     * can offer only the parts that have not begun (a part is started once). A legacy run started
     * before D62 carries a null part and counts as part 1.
     */
    public record PackageRow(long id, String name, String templateKey, String sourceTemplateKey,
                             int templateVersion, List<Long> companyProfileIds, List<Seat> seats,
                             String locale, String status, String publishedAt,
                             List<PartRow> parts, List<Integer> startedParts) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PackageRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** {@code templateKey} is the frozen copy that will run; {@code sourceTemplateKey} the draft. */
    /**
     * {@code seats} is positional: index i is the person in company i, or null for a company left
     * open for a walk-in. Stored as rows so the database can refuse seating one person twice.
     */
    @Transactional
    public long insert(long tenantId, String name, String templateKey, String sourceTemplateKey,
                       int templateVersion, List<Long> companyProfileIds, List<Long> seats,
                       String locale, long publishedBy) {
        long id = jdbc.queryForObject("""
                insert into simulation_package
                    (tenant_id, name, template_key, source_template_key, template_version,
                     company_profile_ids, locale, published_by)
                values (?, ?, ?, ?, ?, ?::jsonb, ?, ?) returning id
                """, Long.class, tenantId, name, templateKey, sourceTemplateKey, templateVersion,
                writeIds(companyProfileIds), locale, publishedBy);
        if (seats != null) {
            for (int position = 0; position < seats.size(); position++) {
                Long userId = seats.get(position);
                if (userId != null) {
                    jdbc.update("""
                            insert into simulation_package_seat (package_id, position, user_id)
                            values (?, ?, ?)
                            """, id, position, userId);
                }
            }
        }
        return id;
    }

    public List<PackageRow> list(long tenantId) {
        return jdbc.query("""
                select id, name, template_key, source_template_key, template_version,
                       company_profile_ids::text, locale, status,
                       to_char(published_at, 'YYYY-MM-DD HH24:MI') as published_at
                from simulation_package where tenant_id = ? order by published_at desc, id desc
                """, (rs, i) -> new PackageRow(rs.getLong("id"), rs.getString("name"),
                rs.getString("template_key"), rs.getString("source_template_key"),
                rs.getInt("template_version"), readIds(rs.getString("company_profile_ids")),
                seatsOf(rs.getLong("id")), rs.getString("locale"), rs.getString("status"),
                rs.getString("published_at"), partsOf(rs.getLong("id")),
                startedPartsOf(tenantId, rs.getLong("id"))),
                tenantId);
    }

    /**
     * How many packages a tenant has ever published, retired ones included. Used as the
     * "has this tenant been set up already" question — a retired package still means people have
     * worked here, and a seeder must not treat that as an empty tenant.
     */
    public int countForTenant(long tenantId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from simulation_package where tenant_id = ?", Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    /** The parts of this package that already have a session: they cannot be started twice. */
    public List<Integer> startedPartsOf(long tenantId, long packageId) {
        return jdbc.queryForList("""
                select distinct coalesce(part_position, 1) from run
                where package_id = ? and tenant_id = ?
                order by 1
                """, Integer.class, packageId, tenantId);
    }

    /** The programme's parts, in order. A single-simulation package has exactly one. */
    public List<PartRow> partsOf(long packageId) {
        return jdbc.query("""
                select position, name, template_key, source_template_key, template_version
                from simulation_package_part where package_id = ? order by position
                """, (rs, i) -> new PartRow(rs.getInt("position"), rs.getString("name"),
                rs.getString("template_key"), rs.getString("source_template_key"),
                rs.getInt("template_version")), packageId);
    }

    public void insertPart(long packageId, int position, String name, String templateKey,
                           String sourceTemplateKey, int templateVersion) {
        jdbc.update("""
                insert into simulation_package_part
                    (package_id, position, name, template_key, source_template_key, template_version)
                values (?, ?, ?, ?, ?, ?)
                """, packageId, position, name, templateKey, sourceTemplateKey, templateVersion);
    }

    /** The runs of one programme, in part order: what the course comparison reads (D62). */
    public List<java.util.Map<String, Object>> partRuns(long tenantId, long packageId) {
        return jdbc.queryForList("""
                select r.id as run_id, r.part_position, r.status,
                       coalesce(s.name, 'Part ' || r.part_position) as part_name
                from run r
                left join simulation_package_part s
                       on s.package_id = r.package_id and s.position = r.part_position
                where r.package_id = ? and r.tenant_id = ?
                order by r.part_position nulls first, r.id
                """, packageId, tenantId);
    }

    /** Profit per company across one run: the part's result, before any comparison. */
    public List<java.util.Map<String, Object>> partProfits(long runId) {
        return jdbc.queryForList("""
                select t.id as team_id, t.company_name,
                       coalesce(sum((rr.score->>'profit')::numeric), 0) as profit
                from team t
                left join round_result rr on rr.team_id = t.id
                     and rr.round_id in (select id from round where run_id = ?)
                where t.run_id = ?
                group by t.id, t.company_name
                order by profit desc, t.id
                """, runId, runId);
    }

    /** The prepared class list for one package, in company order. */
    public List<Seat> seatsOf(long packageId) {
        return jdbc.query("""
                select s.position, u.id, u.display_name, u.email
                from simulation_package_seat s join app_user u on u.id = s.user_id
                where s.package_id = ? order by s.position
                """, (rs, i) -> new Seat(rs.getInt("position"), rs.getLong("id"),
                rs.getString("display_name"), rs.getString("email")), packageId);
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

    /** A package that can be collected: retired, and no session ever ran from it. */
    public record TidyRow(long id, String name, String frozenKey, String publishedAt) {
    }

    /**
     * Retired packages whose sessions are gone. A package that ran a class is history and is never
     * offered here, whatever its status — the guard is "no run points at it", not "it is old".
     */
    public List<TidyRow> collectable(long tenantId) {
        return jdbc.query("""
                select p.id, p.name, p.template_key,
                       to_char(p.published_at, 'YYYY-MM-DD HH24:MI') as published_at
                from simulation_package p
                where p.tenant_id = ? and p.status = 'RETIRED'
                  and not exists (select 1 from run r where r.package_id = p.id)
                order by p.published_at, p.id
                """, (rs, i) -> new TidyRow(rs.getLong("id"), rs.getString("name"),
                rs.getString("template_key"), rs.getString("published_at")), tenantId);
    }

    /**
     * Removes one collectable package and its frozen template copy.
     *
     * <p>Two foreign keys point at each other — {@code scenario.frozen_for_package} → package and
     * {@code package.template_key} → scenario — so the back-reference is cleared first. The catalogue
     * rows (product, channel, rnd_project, scenario_round, environment_event) cascade from scenario.
     */
    @Transactional
    public TidyRow collect(long tenantId, long id) {
        TidyRow row = collectable(tenantId).stream().filter(candidate -> candidate.id() == id)
                .findFirst()
                .orElseThrow(() -> ProblemException.conflict("package.not_collectable",
                        "this package cannot be tidied up: it is still published, or a session ran "
                                + "from it", "package", id));
        jdbc.update("update scenario set frozen_for_package = null where frozen_for_package = ?", id);
        jdbc.update("delete from simulation_package where id = ? and tenant_id = ?", id, tenantId);
        jdbc.update("delete from scenario where key = ?", row.frozenKey());
        return row;
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
