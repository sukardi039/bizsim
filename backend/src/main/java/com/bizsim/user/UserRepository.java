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
