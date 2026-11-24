package com.bizsim.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.io.IOException;
import java.io.InputStream;

/**
 * D119: the one authority for the console's limits.
 *
 * <p>A rule used to live wherever it was enforced — a Java constant, a TypeScript literal, a SQL check
 * constraint, a build-time asset — and the copies drifted in silence. The class size is the example that
 * cost a release: ten in the API, ten in the picker, ten in the palette and <em>six</em> in the map
 * template, so a 7–10 company class could play but not share its map.
 *
 * <p>{@code limits.json} is now the single statement of those numbers. This class reads it, refuses to
 * start with a file that contradicts itself, and hands the values to the server. The console reads the
 * same file through {@code GET /api/limits}, the web build reads it to size the map template and to check
 * the palette, and a test asserts the database constraints agree. Nothing may restate a limit.
 *
 * <p>Fail-fast is deliberate: a class size of {@code min: 12, max: 2} is a configuration that would make
 * every class impossible, and discovering it as a 400 on a facilitator's button is worse than refusing to
 * boot.
 */
@Component
public class Limits {

    /** Where the file is when it is not at the classpath root (a run straight from the repo). */
    private static final String[] FALLBACK_PATHS = {
            "limits.json", "../backend/src/main/resources/limits.json",
    };

    private final int classSizeMin;
    private final int classSizeMax;
    private final int classSizeDefault;
    private final int maxRounds;
    private final int maxProducts;
    /** D135: the competitive positions, in the order the picker shows them. */
    private final List<String> strategies;
    /** D135: the competence levels. */
    private final List<String> levels;
    private final JsonNode raw;

    public Limits() {
        this(load());
    }

    Limits(JsonNode raw) {
        validate(raw);
        this.raw = raw;
        this.classSizeMin = required(raw.path("classSize").path("min"), "classSize.min");
        this.classSizeMax = required(raw.path("classSize").path("max"), "classSize.max");
        this.classSizeDefault = required(raw.path("classSize").path("default"), "classSize.default");
        this.maxRounds = required(raw.path("maxRounds"), "maxRounds");
        this.maxProducts = required(raw.path("maxProducts"), "maxProducts");
        this.strategies = codes(raw.path("strategies").path("positions"), "strategies.positions");
        this.levels = texts(raw.path("levels"), "levels");
    }

    /**
     * Refuses a file that contradicts itself. Package-visible so a test can prove the refusal without
     * shipping a broken file.
     */
    static void validate(JsonNode raw) {
        int classSizeMin = required(raw.path("classSize").path("min"), "classSize.min");
        int classSizeMax = required(raw.path("classSize").path("max"), "classSize.max");
        int classSizeDefault = required(raw.path("classSize").path("default"), "classSize.default");
        int maxRounds = required(raw.path("maxRounds"), "maxRounds");
        int maxProducts = required(raw.path("maxProducts"), "maxProducts");

        if (classSizeMin < 1) {
            throw new IllegalStateException("limits.json: classSize.min must be at least 1");
        }
        if (classSizeMax <= classSizeMin) {
            throw new IllegalStateException("limits.json: classSize.max (" + classSizeMax
                    + ") must be greater than classSize.min (" + classSizeMin + ")");
        }
        if (classSizeDefault < classSizeMin || classSizeDefault > classSizeMax) {
            throw new IllegalStateException("limits.json: classSize.default (" + classSizeDefault
                    + ") must lie between the min and the max");
        }
        if (maxRounds < 1 || maxProducts < 1) {
            throw new IllegalStateException("limits.json: maxRounds and maxProducts must be positive");
        }
        // D135: a position is a (shape, advantage) pair. Porter's grid is two by two, so a list that repeats a
        // pair, or names a shape or advantage outside the grid, is a file that would seed a library nobody can
        // make sense of — refused at startup rather than discovered as a trainer's confusing picker.
        List<String> codes = codes(raw.path("strategies").path("positions"), "strategies.positions");
        if (codes.isEmpty()) {
            throw new IllegalStateException("limits.json: strategies.positions must not be empty");
        }
        if (codes.size() != codes.stream().distinct().count()) {
            throw new IllegalStateException("limits.json: strategies.positions repeats a code");
        }
        Set<String> pairs = new java.util.LinkedHashSet<>();
        for (JsonNode position : raw.path("strategies").path("positions")) {
            String shape = text(position.path("shape"), "strategies.positions[].shape");
            String advantage = text(position.path("advantage"), "strategies.positions[].advantage");
            if (!Set.of("broad", "narrow").contains(shape)) {
                throw new IllegalStateException("limits.json: shape '" + shape
                        + "' must be broad or narrow");
            }
            if (!Set.of("cost", "differentiation").contains(advantage)) {
                throw new IllegalStateException("limits.json: advantage '" + advantage
                        + "' must be cost or differentiation");
            }
            if (!pairs.add(shape + "/" + advantage)) {
                throw new IllegalStateException("limits.json: the " + shape + "/" + advantage
                        + " position is named twice — Porter's grid has four cells, one each");
            }
        }
        if (texts(raw.path("levels"), "levels").isEmpty()) {
            throw new IllegalStateException("limits.json: levels must not be empty");
        }
    }

    /** The codes of an array of objects, in file order. */
    private static List<String> codes(JsonNode positions, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode position : positions) {
            out.add(text(position.path("code"), field + "[].code"));
        }
        return out;
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        int index = 0;
        for (JsonNode node : array) {
            out.add(text(node, field + "[" + index++ + "]"));
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalStateException("limits.json: " + field + " must be a non-empty string");
        }
        return node.asText();
    }

    public int classSizeMin() {
        return classSizeMin;
    }

    public int classSizeMax() {
        return classSizeMax;
    }

    public int classSizeDefault() {
        return classSizeDefault;
    }

    public int maxRounds() {
        return maxRounds;
    }

    public int maxProducts() {
        return maxProducts;
    }

    /** D135: the competitive positions, in the order the picker shows them. */
    public List<String> strategies() {
        return strategies;
    }

    /** D135: the competence levels. */
    public List<String> levels() {
        return levels;
    }

    /** D135: the size of the auto-player library, derived so nobody hand-writes it. */
    public int autoWorkerLibrarySize() {
        return strategies.size() * levels.size();
    }

    /** What {@code GET /api/limits} answers — the file itself, so the console cannot read it differently. */
    public JsonNode asJson() {
        return raw;
    }

    private static JsonNode load() {
        try (InputStream resource = Limits.class.getClassLoader().getResourceAsStream("limits.json")) {
            if (resource != null) {
                return new ObjectMapper().readTree(resource);
            }
        } catch (IOException e) {
            throw new IllegalStateException("limits.json is unreadable", e);
        }
        for (String path : FALLBACK_PATHS) {
            File file = new File(path);
            if (file.isFile()) {
                try {
                    return new ObjectMapper().readTree(file);
                } catch (IOException e) {
                    throw new IllegalStateException("limits.json is unreadable at " + path, e);
                }
            }
        }
        throw new IllegalStateException("limits.json is missing: it is the single authority for the "
                + "console's limits (class size, rounds, products) and the server will not start without it");
    }

    private static int required(JsonNode node, String name) {
        if (!node.isInt()) {
            throw new IllegalStateException("limits.json: " + name + " must be an integer");
        }
        return node.asInt();
    }
}
