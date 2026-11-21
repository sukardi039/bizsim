package com.bizsim.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.File;
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
