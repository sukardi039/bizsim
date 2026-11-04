package com.bizsim.web;

import com.bizsim.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The deploy probe: unauthenticated, and specific enough to tell whether a redeploy landed.
 *
 * <p>It must work before sign-in (that is when you need it) and it must name the latest migration,
 * because that — not a version string — is what proves the schema is current.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VersionApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Test
    void version_reports_the_applied_migrations_without_a_login() throws Exception {
        mvc.perform(get("/api/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").isString())
                // Baked at build time: the only signal that distinguishes two deploys with the same
                // migration count.
                .andExpect(jsonPath("$.builtAt").isString())
                .andExpect(jsonPath("$.migrations").value(18))
                .andExpect(jsonPath("$.latest").value("18"))
                .andExpect(jsonPath("$.latestAppliedAt").isString())
                .andExpect(jsonPath("$.serverTime").isString());
    }
}
