package com.bizsim.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Every lookup is tenant-scoped by construction. There is no {@code findById(long)} without a
 * tenant argument, so a missing tenant filter is a compile error rather than a data leak.
 */
@Repository
public class UserRepository {

    public record AppUser(long id, long tenantId, String email, String passwordHash,
                          String displayName, String role, boolean active) {
    }

    private static final RowMapper<AppUser> MAPPER = (rs, i) -> new AppUser(
            rs.getLong("id"),
            rs.getLong("tenant_id"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getString("role"),
            rs.getBoolean("active"));

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Login lookup: tenant is identified by its public code, so the same email can exist twice. */
    public Optional<AppUser> findByTenantCodeAndEmail(String tenantCode, String email) {
        List<AppUser> rows = jdbc.query("""
                select u.* from app_user u
                join tenant t on t.id = u.tenant_id
                where t.code = ? and u.email = ? and u.active
                """, MAPPER, tenantCode, email);
        return rows.stream().findFirst();
    }

    public Optional<AppUser> findByIdInTenant(long id, long tenantId) {
        List<AppUser> rows = jdbc.query(
                "select * from app_user where id = ? and tenant_id = ?", MAPPER, id, tenantId);
        return rows.stream().findFirst();
    }

    /**
     * Deletes a trainee account outright. Only safe for someone who never played: a submitted
     * decision references the account that made it, and rewriting history is not an option, so the
     * service asks first and the database refuses anyway.
     */
    public boolean delete(long tenantId, long id) {
        int deleted = jdbc.update(
                "delete from app_user where id = ? and tenant_id = ? and role = 'PLAYER'",
                id, tenantId);
        return deleted > 0;
    }

    /** True once the trainee has submitted anything: their account is history, not a spare row. */
    public boolean hasHistory(long tenantId, long id) {
        Boolean played = jdbc.queryForObject("""
                select exists (select 1 from decision d
                               join team t on t.id = d.team_id
                               join run r on r.id = t.run_id
                               where d.submitted_by = ? and r.tenant_id = ?)
                """, Boolean.class, id, tenantId);
        return Boolean.TRUE.equals(played);
    }

    /**
     * Turns an account off (or back on). A trainee who leaves the school is deactivated, not deleted:
     * the login stops working, the seats and the results stay readable for the debrief.
     */
    public boolean setActive(long tenantId, long id, boolean active) {
        int changed = jdbc.update(
                "update app_user set active = ? where id = ? and tenant_id = ? and role = 'PLAYER'",
                active, id, tenantId);
        return changed > 0;
    }

    /** One account of one tenant, for the roster checks at session start. */
    public java.util.Optional<AppUser> findInTenant(long tenantId, long id) {
        return jdbc.query("select * from app_user where id = ? and tenant_id = ?",
                MAPPER, id, tenantId).stream().findFirst();
    }

    /**
     * The language this person sees: their own choice, else the tenant default, else English. One
     * query so the resolution order lives in one place.
     */
    public String localeOf(long tenantId, long userId) {
        return jdbc.query("""
                select coalesce(u.locale, t.locale, 'en')
                from app_user u join tenant t on t.id = u.tenant_id
                where u.id = ? and u.tenant_id = ?
                """, (rs, i) -> rs.getString(1), userId, tenantId).stream().findFirst().orElse("en");
    }

    /** Sets the person's own language. Null means "follow the tenant" again. */
    public boolean setLocale(long tenantId, long userId, String locale) {
        return jdbc.update("update app_user set locale = ? where id = ? and tenant_id = ?",
                locale, userId, tenantId) > 0;
    }

    /** Every account of one tenant, for the facilitator's trainee list. Never returns password_hash. */
    public List<AppUser> findAllInTenant(long tenantId) {
        return jdbc.query("select * from app_user where tenant_id = ? order by role, email",
                MAPPER, tenantId);
    }

    public Optional<AppUser> findByEmailInTenant(long tenantId, String email) {
        return jdbc.query("select * from app_user where tenant_id = ? and email = ?",
                MAPPER, tenantId, email).stream().findFirst();
    }

    /** Creates an account inside the caller's tenant. The tenant never comes from the request body. */
    public AppUser insert(long tenantId, String email, String displayName, String passwordHash,
                          String role) {
        return jdbc.queryForObject("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, ?)
                returning *
                """, MAPPER, tenantId, email, passwordHash, displayName, role);
    }
}
