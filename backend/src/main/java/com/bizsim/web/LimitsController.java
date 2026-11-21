package com.bizsim.web;

import com.bizsim.config.Limits;
import com.bizsim.tenant.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * D119: the console reads its limits from the server, not from its own literals.
 *
 * <p>Every number in this answer is the content of {@code limits.json}, the same file the server validates
 * at startup and the same one the web build reads to size the map template. That is what makes the rule
 * universal: there is one copy, and the console is reading it rather than agreeing with it.
 *
 * <p>Signed-in only — the limits are not a secret, but they are console behaviour, and an unauthenticated
 * endpoint would be one more thing to keep in step with the class language and tenant resolution.
 */
@RestController
@RequestMapping("/api/limits")
public class LimitsController {

    private final Limits limits;

    public LimitsController(Limits limits) {
        this.limits = limits;
    }

    @GetMapping
    public JsonNode limits() {
        TenantContext.require();
        return limits.asJson();
    }
}
