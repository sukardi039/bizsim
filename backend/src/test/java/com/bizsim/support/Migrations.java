package com.bizsim.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The migration folder as numbers, so a test that asserts "everything was applied" does not have to
 * be edited every time a migration is added — it only fails when the applied history and the folder
 * disagree, which is the thing worth failing on.
 */
public final class Migrations {

    private static final Path DIRECTORY = Paths.get("src/main/resources/db/migration");
    private static final Pattern NAME = Pattern.compile("^V(\\d+)__[a-z0-9_]+\\.sql$");

    private Migrations() {
    }

    public static int count() {
        try (Stream<Path> files = Files.list(DIRECTORY)) {
            return (int) files.filter(path -> NAME.matcher(path.getFileName().toString()).matches())
                    .count();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + DIRECTORY, e);
        }
    }

    public static int latest() {
        try (Stream<Path> files = Files.list(DIRECTORY)) {
            return files.map(path -> NAME.matcher(path.getFileName().toString()))
                    .filter(Matcher::matches)
                    .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                    .max()
                    .orElse(0);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + DIRECTORY, e);
        }
    }
}
