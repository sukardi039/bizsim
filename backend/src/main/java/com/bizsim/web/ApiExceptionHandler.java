package com.bizsim.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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
        String reason = e.getReason() == null ? e.getStatusCode().toString() : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                "code", "error.http." + e.getStatusCode().value(),
                "error", reason));
    }

    /** Bean-validation on request bodies: a field-level code, not a Spring type name. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> fields(MethodArgumentNotValidException e) {
        List<Map<String, Object>> problems = e.getBindingResult().getFieldErrors().stream()
                .map(error -> body(ApiProblem.of("request.field",
                        error.getField() + " " + error.getDefaultMessage(),
                        "field", error.getField())))
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code", "request.invalid",
                "error", "invalid request",
                "problems", problems));
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
