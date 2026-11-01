package com.bizsim.auth;

import com.bizsim.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Over real HTTP (random port), not MockMvc: proves the servlet filter, the JSON serialisation and
 * the status codes as a browser would see them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpSmokeTest extends PostgresTestBase {

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void login_then_read_identity_over_http() {
        Integer existing = jdbc.queryForObject(
                "select count(*) from tenant where code = 'http-smoke'", Integer.class);
        if (existing == null || existing == 0) {
            Long tenantId = jdbc.queryForObject(
                    "insert into tenant (code, name) values ('http-smoke', 'HTTP Smoke') returning id",
                    Long.class);
            jdbc.update("""
                    insert into app_user (tenant_id, email, password_hash, display_name, role)
                    values (?, 'smoke@test.local', ?, 'Smoke', 'FACILITATOR')
                    """, tenantId, new BCryptPasswordEncoder().encode("smoke-pass"));
        }

        ResponseEntity<Map> login = http.postForEntity("/api/auth/login", Map.of(
                "tenantCode", "http-smoke",
                "email", "smoke@test.local",
                "password", "smoke-pass"), Map.class);

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) login.getBody().get("token");
        assertThat(token).isNotBlank();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> me = http.exchange("/api/me", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).containsEntry("tenantCode", "http-smoke")
                .containsEntry("role", "FACILITATOR");

        // No token: 401 over the wire, not a redirect to a login page.
        ResponseEntity<String> anonymous = http.getForEntity("/api/me", String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.getBody()).contains("missing bearer token");
    }
}
