package com.bizsim.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Boots one real PostgreSQL for the whole test JVM.
 *
 * <p>Tests run against actual PostgreSQL, not H2 in compatibility mode: the schema uses
 * {@code jsonb}, identity columns and partial-free indexes, and a green H2 run would prove none of
 * them. No Docker and no root are required — the binaries are a test-scoped Maven dependency.
 */
public abstract class PostgresTestBase {

    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        EmbeddedPostgres pg = ensureStarted();
        registry.add("spring.datasource.url", () -> pg.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("bizsim.jwt.secret",
                () -> "test-secret-0123456789abcdef0123456789abcdef");
    }

    /** Starts the shared instance on first use; also usable from tests that need no Spring context. */
    protected static synchronized EmbeddedPostgres ensureStarted() {
        if (postgres == null) {
            try {
                postgres = EmbeddedPostgres.builder().setPort(0).start();
            } catch (IOException e) {
                throw new UncheckedIOException("cannot start embedded postgres", e);
            }
        }
        return postgres;
    }

    /** URL for another database on the same embedded server, whether or not it exists yet. */
    protected static String urlFor(String database) {
        return ensureStarted().getJdbcUrl("postgres", database);
    }
}
