package com.bizsim.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D119: {@code limits.json} is the single authority, and the copies must agree with it.
 *
 * <p>The defect this prevents: a rule restated wherever it was enforced. Ten companies in the API, ten in
 * the builder's picker, ten in the palette and <strong>six</strong> in the map template — a class of seven
 * could play and not share its map. Nothing failed when the fifth copy appeared, which is the actual
 * problem. So this test does not check "the limit is ten"; it checks that every statement of the limit
 * still says the same thing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LimitsTest extends com.bizsim.support.PostgresTestBase {

    @Autowired
    Limits limits;
    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    @Test
    void the_file_is_valid_and_the_server_reads_it() {
        assertThat(limits.classSizeMin()).isGreaterThanOrEqualTo(1);
        assertThat(limits.classSizeMax()).isGreaterThan(limits.classSizeMin());
        assertThat(limits.classSizeDefault()).isBetween(limits.classSizeMin(), limits.classSizeMax());
        assertThat(limits.maxRounds()).isPositive();
        assertThat(limits.maxProducts()).isPositive();
    }

    @Test
    void the_database_constraint_says_the_same_thing() {
        // A backstop in the database that disagrees with the server is worse than none: the API accepts a
        // size the insert refuses, and the error surfaces as a 500 mid-class.
        String definition = jdbc.queryForObject("""
                select pg_get_constraintdef(c.oid)
                from pg_constraint c join pg_class t on t.oid = c.conrelid
                where t.relname = 'simulation_package'
                  and c.conname like '%seat_limit%'
                """, String.class);
        assertThat(definition).as("simulation_package.seat_limit check")
                .contains(String.valueOf(limits.classSizeMin()))
                .contains(String.valueOf(limits.classSizeMax()));
    }

    @Test
    void the_console_reads_them_from_the_server() throws Exception {
        long tenantId = jdbc.queryForObject("""
                insert into tenant (code, name) values ('limits', 'Limits') returning id
                """, Long.class);
        String email = "reader@limits.test";
        jdbc.update("""
                insert into app_user (tenant_id, email, password_hash, display_name, role)
                values (?, ?, ?, ?, 'FACILITATOR')
                """, tenantId, email, new BCryptPasswordEncoder().encode("pw"), email);
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", "limits", "email", email, "password", "pw"))))
                .andExpect(status().isOk())
                .andReturn();
        String token = json.readTree(login.getResponse().getContentAsString()).get("token").asText();

        MvcResult answer = mvc.perform(get("/api/limits").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classSize.max").value(limits.classSizeMax()))
                .andExpect(jsonPath("$.classSize.min").value(limits.classSizeMin()))
                .andExpect(jsonPath("$.classSize.default").value(limits.classSizeDefault()))
                .andExpect(jsonPath("$.maxRounds").value(limits.maxRounds()))
                .andExpect(jsonPath("$.maxProducts").value(limits.maxProducts()))
                .andReturn();
        JsonNode body = json.readTree(answer.getResponse().getContentAsString());
        assertThat(body.path("classSize").path("max").asInt()).isEqualTo(limits.classSizeMax());
    }

    @Test
    void a_file_that_contradicts_itself_refuses_to_load() {
        // Not "does it boot" — a class size whose minimum exceeds its maximum would make every class
        // impossible, and that must fail at startup, not as a 400 on a facilitator's button.
        assertThatThrownBy(() -> {
            JsonNode bad = new ObjectMapper().readTree("""
                    {"classSize":{"min":12,"max":2,"default":10},"maxRounds":48,"maxProducts":10}
                    """);
            Limits.validate(bad);
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be greater than");
    }
}
