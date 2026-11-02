package com.bizsim.web;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The decision is well-formed but cannot be paid for (D7). Carries the breakdown so the form can show
 * which line is over budget instead of just refusing.
 */
public class CashShortfallException extends RuntimeException {

    private final BigDecimal cash;
    private final BigDecimal required;
    private final Map<String, BigDecimal> lines;

    public CashShortfallException(BigDecimal cash, BigDecimal required, Map<String, BigDecimal> lines) {
        super("insufficient cash");
        this.cash = cash;
        this.required = required;
        this.lines = lines;
    }

    public BigDecimal cash() {
        return cash;
    }

    public BigDecimal required() {
        return required;
    }

    public Map<String, BigDecimal> lines() {
        return lines;
    }
}
