package com.bizsim.web;

import com.bizsim.support.PostgresTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D120: a validation failure names the field, and never carries a sentence the console cannot translate.
 *
 * <p>The defect this pins down: a Chinese class typing a blank password saw <strong>"password is not
 * valid."</strong> in English. The server was answering with Spring's own constraint message
 * ({@code getDefaultMessage()}), which the console had no key for, so it printed the English verbatim while
 * every label around it was Chinese.
 *
 * <p>The contract now: the answer carries {@code code=request.field} and a {@code field} the console maps
 * through its own catalogue, plus the constraint's <em>kind</em> ({@code violation}) so an empty field can
 * read "please fill this in" rather than the same generic sentence as a malformed one. No English prose.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FieldProblemApiTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    @Test
    void a_blank_field_answers_with_a_code_and_a_field_name() throws Exception {
        // No tenant seeded: the request fails bean validation before any lookup, which is the case that
        // reaches a Chinese screen first — a person who typed nothing into one box.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", "demo", "email", "someone@demo.test", "password", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("request.invalid"))
                .andExpect(jsonPath("$.problems[0].code").value("request.field"))
                .andExpect(jsonPath("$.problems[0].params.field").value("password"))
                // The kind of violation travels, so the console can say "please fill this in".
                .andExpect(jsonPath("$.problems[0].params.violation").value("NotBlank"))
                // What it must NOT do: hand the screen an English sentence to print as-is.
                .andExpect(jsonPath("$.problems[0].params.message").doesNotExist())
                .andExpect(jsonPath("$.problems[0].error").doesNotExist());
    }

    @Test
    void a_bad_sign_in_answers_with_a_translatable_code() throws Exception {
        // D120: the answer used to be the English sentence "invalid credentials", which a Chinese class saw
        // verbatim. The cause is still indistinguishable — every bad login gets this one body — but the
        // screen can now say it in the class language.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "tenantCode", "nobody-here", "email", "someone@nowhere.test",
                                "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.invalid_credentials"));
    }
}
