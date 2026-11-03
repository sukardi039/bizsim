package com.bizsim.sim;

import com.bizsim.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Simulation templates: the economy, catalogue and settings a session is played on.
 *
 * <p>Ruling R2 (3 Nov 2026): the shipped `standard-v1` stays global and read-only, and a tenant that
 * needs a different economy copies it and edits the copy. This controller is the read half plus the
 * copy; editing arrives with S6c.
 */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    public record CopyRequest(String name) {
    }

    private final ScenarioRepository scenarios;

    public TemplateController(ScenarioRepository scenarios) {
        this.scenarios = scenarios;
    }

    @GetMapping
    public List<ScenarioRepository.TemplateRow> list() {
        TenantContext.Principal me = requireFacilitator();
        return scenarios.listVisible(me.tenantId());
    }

    @PostMapping("/{key}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    public ScenarioRepository.TemplateRow copy(@PathVariable String key,
                                               @RequestBody(required = false) CopyRequest request) {
        TenantContext.Principal me = requireFacilitator();
        String copy = scenarios.copy(key, me.tenantId(), me.tenantCode(),
                request == null ? null : request.name());
        return scenarios.findVisible(me.tenantId(), copy).orElseThrow();
    }

    private static TenantContext.Principal requireFacilitator() {
        TenantContext.Principal me = TenantContext.require();
        if (!"FACILITATOR".equals(me.role()) && !"ADMIN".equals(me.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "facilitator only");
        }
        return me;
    }
}
