package com.bizsim.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A status code plus a translatable {@link ApiProblem}. Thrown wherever the caller can do something
 * about the failure; the exception handler renders it as {@code {code, params, error}}.
 */
public class ProblemException extends ResponseStatusException {

    private final ApiProblem problem;

    public ProblemException(HttpStatus status, ApiProblem problem) {
        super(status, problem.message());
        this.problem = problem;
    }

    public ApiProblem problem() {
        return problem;
    }

    // ---- the four shapes that cover the API; each keeps the English message as a fallback ----

    public static ProblemException badRequest(String code, String message, Object... params) {
        return new ProblemException(HttpStatus.BAD_REQUEST, ApiProblem.of(code, message, params));
    }

    public static ProblemException forbidden(String code, String message, Object... params) {
        return new ProblemException(HttpStatus.FORBIDDEN, ApiProblem.of(code, message, params));
    }

    public static ProblemException notFound(String code, String message, Object... params) {
        return new ProblemException(HttpStatus.NOT_FOUND, ApiProblem.of(code, message, params));
    }

    public static ProblemException conflict(String code, String message, Object... params) {
        return new ProblemException(HttpStatus.CONFLICT, ApiProblem.of(code, message, params));
    }
}
