package com.bizsim.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.validation.FieldError;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One error shape for the whole API (D23): `code` + `params` for the interface to translate, plus the
 * English `error`/`details` for logs and for any client that does not know the code yet.
 *
 * <p>Database constraint violations are client errors, not 500s: the only constraints a client can hit
 * are unique keys (a duplicate team name, a player already on a team) and bad references.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> invalid(ValidationException e) {
        List<Map<String, Object>> problems = e.problems().stream().map(ApiExceptionHandler::body).toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code", "decision.invalid",
                "error", "invalid decision",
                "problems", problems,
                // kept for clients written before the code contract
                "details", e.errors()));
    }

    @ExceptionHandler(CashShortfallException.class)
    public ResponseEntity<Map<String, Object>> shortfall(CashShortfallException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "decision.cash_shortfall",
                "error", "insufficient cash",
                "cash", e.cash(),
                "required", e.required(),
                "lines", e.lines()));
    }

    @ExceptionHandler(ProblemException.class)
    public ResponseEntity<Map<String, Object>> problem(ProblemException e) {
        Map<String, Object> body = new LinkedHashMap<>(body(e.problem()));
        body.put("error", e.problem().message());
        return ResponseEntity.status(e.getStatusCode()).body(body);
    }

    /** Anything else thrown with a status: still a code, so the client never shows raw prose alone. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        // D120: a reason that is a dotted app code (`auth.invalid_credentials`) travels as the code, so the
        // console can translate it. Anything else stays an HTTP-shaped answer with its prose — the fallback
        // is deliberate: a raw sentence must never masquerade as a translatable code.
        String reason = e.getReason();
        String code = reason != null && reason.matches("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+")
                ? reason
                : "error.http." + e.getStatusCode().value();
        return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                "code", code,
                "error", reason == null ? e.getStatusCode().toString() : reason));
    }

    /**
     * Bean-validation on request bodies: a field-level code, not a Spring type name.
     *
     * <p>D120: a failed validation says <em>which field</em>, not what a constraint was called. The console
     * renders {@code request.field} through the class language ("{field} is not valid." / "{field} 不合法。")
     * and translates the field name through the resource's own catalogue, so a Chinese class reads Chinese
     * here. The English sentence this used to carry — Spring's {@code getDefaultMessage()} — reached the
     * screen untranslated in every other language.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> fields(MethodArgumentNotValidException e) {
        List<Map<String, Object>> problems = e.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::fieldProblem)
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code", "request.invalid",
                "error", "invalid request",
                "problems", problems));
    }

    private static Map<String, Object> fieldProblem(FieldError error) {
        return fieldProblem(error.getField(), error.isBindingFailure() ? null : error.getCode());
    }

    /**
     * One field problem. {@code violation} separates "you left it empty" from "it was not a number", so the
     * console can say "please fill this in" rather than a generic "not valid" for both.
     */
    private static Map<String, Object> fieldProblem(String field, String violation) {
        // Passed as varargs pairs, not as a prebuilt map: ApiProblem.of copies pairs, and an empty params
        // object is the bug this test exists for — the console then has no field name to translate.
        return violation == null
                ? body(ApiProblem.of("request.field", field, "field", field))
                : body(ApiProblem.of("request.field", field, "field", field, "violation", violation));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "db.conflict", "error", "duplicate or conflicting value"));
    }

    private static Map<String, Object> body(ApiProblem problem) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", problem.code());
        body.put("params", problem.params());
        body.put("message", problem.message());
        return body;
    }
}
