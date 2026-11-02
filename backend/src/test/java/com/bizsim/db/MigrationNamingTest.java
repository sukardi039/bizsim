package com.bizsim.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lint for the migration folder. These are the two mistakes that actually happen in a team:
 *
 * <ul>
 *   <li>two people both create V5 on their own branch (duplicate or gap in the numbering);</li>
 *   <li>someone writes a {@code drop table} in a migration and the next deploy deletes a class's
 *       history.</li>
 * </ul>
 *
 * A destructive statement is not banned outright — decommissioning a table is legitimate — but it
 * needs the marker {@code -- migration:allow-destructive} and therefore a reviewer's attention.
 */
class MigrationNamingTest {

    private static final Path DIRECTORY = Paths.get("src/main/resources/db/migration");
    private static final Pattern NAME = Pattern.compile("^V(\\d+)__[a-z0-9_]+\\.sql$");
    private static final Pattern DESTRUCTIVE = Pattern.compile("(?i)\\b(drop\\s+table|truncate\\s+table)\\b");
    private static final String ALLOWANCE = "-- migration:allow-destructive";

    /**
     * Statements PostgreSQL refuses to run inside a transaction block. A migration containing one
     * runs in autocommit mode, which is exactly how a half-applied change becomes possible.
     */
    private static final Pattern NON_TRANSACTIONAL = Pattern.compile(
            "(?im)^\\s*vacuum\\b"
                    + "|create\\s+(unique\\s+)?index\\s+concurrently"
                    + "|reindex\\b.*concurrently"
                    + "|create\\s+database|drop\\s+database"
                    + "|create\\s+tablespace|drop\\s+tablespace"
                    + "|alter\\s+system");
    private static final String NON_TRANSACTIONAL_ALLOWANCE = "-- migration:allow-non-transactional";

    @Test
    void versions_are_contiguous_and_named_by_convention() {
        List<String> names = files();

        assertThat(names).isNotEmpty();
        List<Integer> versions = new ArrayList<>();
        for (String name : names) {
            Matcher matcher = NAME.matcher(name);
            assertThat(matcher.matches())
                    .as("%s must look like V<n>__lower_snake_case.sql", name)
                    .isTrue();
            versions.add(Integer.parseInt(matcher.group(1)));
        }
        for (int i = 0; i < versions.size(); i++) {
            assertThat(versions.get(i))
                    .as("%s must be version %d: a gap or duplicate breaks ordering for everyone", names.get(i), i + 1)
                    .isEqualTo(i + 1);
        }
    }

    @Test
    void destructive_statements_need_an_explicit_allowance() {
        for (String name : files()) {
            String sql = read(name);
            if (DESTRUCTIVE.matcher(sql).find()) {
                assertThat(sql)
                        .as("%s drops or truncates a table; add %s and say why in a comment", name, ALLOWANCE)
                        .contains(ALLOWANCE);
            }
        }
    }

    /**
     * This is the guard that keeps the "a migration either applies completely or not at all"
     * guarantee true. One `create index concurrently` in a file is enough to lose it, because
     * PostgreSQL cannot run that statement inside a transaction.
     */
    @Test
    void statements_that_cannot_run_in_a_transaction_need_an_allowance() {
        for (String name : files()) {
            String sql = read(name);
            if (NON_TRANSACTIONAL.matcher(sql).find()) {
                assertThat(sql)
                        .as("%s contains a statement PostgreSQL cannot run in a transaction, so the "
                                + "file is no longer atomic; add %s and document the recovery plan",
                                name, NON_TRANSACTIONAL_ALLOWANCE)
                        .contains(NON_TRANSACTIONAL_ALLOWANCE);
            }
        }
    }

    private static List<String> files() {
        assertThat(DIRECTORY).as("tests run from the backend module").isDirectory();
        try (Stream<Path> entries = Files.list(DIRECTORY)) {
            return entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(String name) {
        try {
            return Files.readString(DIRECTORY.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
