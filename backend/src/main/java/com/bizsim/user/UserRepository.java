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
}
