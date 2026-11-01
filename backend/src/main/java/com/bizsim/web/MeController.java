package com.bizsim.web;

import com.bizsim.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity probe. Its second job is to be the cheapest possible proof that tenant isolation works:
 * the tenant in the response comes from the token, not from the request.
 */
@RestController
@RequestMapping("/api")
public class MeController {

    public record Me(long userId, long tenantId, String tenantCode, String role) {
    }

    @GetMapping("/ping")
    public String ping() {
        return "ok";
    }

    @GetMapping("/me")
    public Me me() {
        TenantContext.Principal p = TenantContext.require();
        return new Me(p.userId(), p.tenantId(), p.tenantCode(), p.role());
    }
}
