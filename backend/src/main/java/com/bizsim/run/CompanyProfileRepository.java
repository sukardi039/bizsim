package com.bizsim.run;

import com.bizsim.web.ProblemException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Company profiles: the reusable definition of a company (S6e).
 *
 * <p>Visible to a tenant = the shipped global profile plus its own. Editing is limited to its own,
 * for the same reason templates are (D22): a shared base row is a reference, not a draft.
 */
@Repository
public class CompanyProfileRepository {

    public record ProfileRow(long id, String name, String companyName, List<String> products,
                             String notes, Long tenantId, Long basedOn) {
        public boolean editable(long tenantId) {
            return tenantId != 0 && this.tenantId != null && this.tenantId == tenantId;
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CompanyProfileRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<ProfileRow> listVisible(long tenantId) {
        return jdbc.query("""
                select id, name, company_name, products::text, notes, tenant_id, based_on
                from company_profile
                where tenant_id is null or tenant_id = ?
                order by tenant_id nulls first, name
                """, (rs, i) -> new ProfileRow(rs.getLong("id"), rs.getString("name"),
                rs.getString("company_name"), readCodes(rs.getString("products")),
                rs.getString("notes"), (Long) rs.getObject("tenant_id"),
                (Long) rs.getObject("based_on")), tenantId);
    }

    public Optional<ProfileRow> findVisible(long tenantId, long id) {
        return listVisible(tenantId).stream().filter(p -> p.id() == id).findFirst();
    }

    public long insert(long tenantId, String name, String companyName, List<String> products,
                       String notes, Long basedOn) {
        return jdbc.queryForObject("""
                insert into company_profile (tenant_id, name, company_name, products, notes, based_on)
                values (?, ?, ?, ?::jsonb, ?, ?) returning id
                """, Long.class, tenantId, name, companyName, writeCodes(products), notes, basedOn);
    }

    public void update(long tenantId, long id, String name, String companyName, List<String> products,
                       String notes) {
        int changed = jdbc.update("""
                update company_profile
                set name = ?, company_name = ?, products = ?::jsonb, notes = ?
                where id = ? and tenant_id = ?
                """, name, companyName, writeCodes(products), notes, id, tenantId);
        if (changed == 0) {
            throw ProblemException.notFound("company_profile.not_found", "no such profile in this tenant");
        }
    }

    /** Copies a visible profile into a tenant-owned one; a copy is what gets edited. */
    @Transactional
    public ProfileRow copy(long tenantId, long id) {
        ProfileRow source = findVisible(tenantId, id).orElseThrow(
                () -> ProblemException.notFound("company_profile.not_found", "no such profile"));
        long copyId = insert(tenantId, source.name() + " (copy)", source.companyName(),
                source.products(), source.notes(), source.id());
        return findVisible(tenantId, copyId).orElseThrow();
    }

    /** The profile for a run's company, resolved once so a badge and the team row cannot disagree. */
    public Optional<ProfileRow> findForTenant(long tenantId, long id) {
        return jdbc.query("""
                select id, name, company_name, products::text, notes, tenant_id, based_on
                from company_profile where id = ? and (tenant_id is null or tenant_id = ?)
                """, (rs, i) -> new ProfileRow(rs.getLong("id"), rs.getString("name"),
                rs.getString("company_name"), readCodes(rs.getString("products")),
                rs.getString("notes"), (Long) rs.getObject("tenant_id"),
                (Long) rs.getObject("based_on")), id, tenantId).stream().findFirst();
    }

    private List<String> readCodes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("unreadable company profile products", e);
        }
    }

    private String writeCodes(List<String> codes) {
        try {
            return json.writeValueAsString(codes == null ? List.of() : codes);
        } catch (Exception e) {
            throw new IllegalStateException("cannot write product codes", e);
        }
    }

    static ProblemException invalid(String code, String message, Object... params) {
        return new ProblemException(HttpStatus.BAD_REQUEST,
                com.bizsim.web.ApiProblem.of(code, message, params));
    }
}
