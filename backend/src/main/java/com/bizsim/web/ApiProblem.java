package com.bizsim.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A failure the client is expected to render, as a **code plus parameters** rather than a sentence.
 *
 * <p>The server never decides what a message says (D23): it says which rule failed and what was
 * involved, and the interface owns the wording in whatever language the person chose. The English
 * `message` travels alongside for diagnostics, logs and any client that does not know the code — so
 * adding a code is backwards compatible and adding a language needs no server change.
 */
public record ApiProblem(String code, Map<String, Object> params, String message) {

    /** {@code of("price.not_positive", "price.P1: price must be > 0", "product", "P1")}. */
    public static ApiProblem of(String code, String message, Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            params.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return new ApiProblem(code, Map.copyOf(params), message);
    }
}
