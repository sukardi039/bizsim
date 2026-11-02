package com.bizsim.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Database constraint violations are client errors, not 500s: the only constraints a client can hit
 * are unique keys (a duplicate team name, a player already on a team) and bad references.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> invalid(ValidationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "invalid decision", "details", e.errors()));
    }

    @ExceptionHandler(CashShortfallException.class)
    public ResponseEntity<Map<String, Object>> shortfall(CashShortfallException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "insufficient cash",
                "cash", e.cash(),
                "required", e.required(),
                "lines", e.lines()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "duplicate or conflicting value"));
    }
}
