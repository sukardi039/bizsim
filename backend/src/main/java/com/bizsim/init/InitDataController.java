package com.bizsim.init;

import com.bizsim.tenant.TenantContext;
import com.bizsim.web.ProblemException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The same init data the boot runner creates, asked for explicitly by an admin of an existing
 * deployment. Useful when the environment cannot be changed (a managed host) or after a failed boot
 * attempt: sign in as an admin, call this once, and the tenant is ready.
 *
 * <p>Admin-only, and only for a tenant that has never published a package. It is not a reset: there
 * is deliberately no way to make it wipe or replace anything.
 */
@RestController
@RequestMapping("/api/admin")
public class InitDataController {

    /** Every field is optional; omitted means the same default the boot runner uses. */
    public record InitRequest(String packageName, String runName, Integer companies, Integer trainees,
                              Integer rounds, Integer cycles, String locale, String password) {
    }

    private final InitDataService initData;

    public InitDataController(InitDataService initData) {
        this.initData = initData;
    }

    @PostMapping("/init-data")
    @ResponseStatus(HttpStatus.CREATED)
    public InitDataService.Summary init(@RequestBody(required = false) InitRequest request) {
        TenantContext.Principal me = TenantContext.require();
        if (!"ADMIN".equals(me.role())) {
            throw ProblemException.forbidden("init.admin_only",
                    "only an admin may create the initial data");
        }
        InitRequest body = request == null ? new InitRequest(null, null, null, null, null, null,
                null, null) : request;
        InitDataService.Params defaults = InitDataService.Params.defaults();
        return initData.init(me.tenantCode(), new InitDataService.Params(
                body.companies() == null ? defaults.companies() : body.companies(),
                body.trainees() == null ? defaults.trainees() : body.trainees(),
                body.rounds() == null ? defaults.rounds() : body.rounds(),
                body.cycles(),
                body.locale() == null ? defaults.locale() : body.locale(),
                body.password(),
                null, body.packageName(), body.runName()));
    }
}
