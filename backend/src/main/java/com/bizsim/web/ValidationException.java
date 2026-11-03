package com.bizsim.web;

import java.util.List;

/**
 * Every rule a submission broke, as codes (see {@link ApiProblem}), never just the first one.
 *
 * <p>{@link #errors()} keeps the English messages for logs and for clients that predate the code
 * contract; {@link #problems()} is what the interface actually renders.
 */
public class ValidationException extends RuntimeException {

    private final List<ApiProblem> problems;

    public ValidationException(List<ApiProblem> problems) {
        super("invalid decision");
        this.problems = List.copyOf(problems);
    }

    public List<ApiProblem> problems() {
        return problems;
    }

    public List<String> errors() {
        return problems.stream().map(ApiProblem::message).toList();
    }
}
