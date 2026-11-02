package com.bizsim.web;

import java.util.List;

/** A decision that is wrong in shape or references. All problems are reported together. */
public class ValidationException extends RuntimeException {

    private final List<String> errors;

    public ValidationException(List<String> errors) {
        super("invalid decision");
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
