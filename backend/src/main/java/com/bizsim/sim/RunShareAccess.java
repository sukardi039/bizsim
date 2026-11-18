package com.bizsim.sim;

import com.bizsim.run.RunRepository;
import com.bizsim.tenant.TenantContext;
import com.bizsim.web.ProblemException;
import org.springframework.stereotype.Component;

/**
 * Who may turn a session's figures into a link.
 *
 * <p>Deliberately the same rule the class standings follow: a facilitator runs the session and may share
 * any of it; a trainee sees their own run's standings already, so sharing them adds nothing they cannot
 * already read. Everyone else — another tenant, a builder who does not run this class — is refused.
 *
 * <p>Kept in one place because the share endpoint, the list and the revoke all have to agree: a link that
 * one role may create and another may not withdraw is how a shared page outlives the class it described.
 */
@Component
public class RunShareAccess {

    private final RunRepository runs;

    public RunShareAccess(RunRepository runs) {
        this.runs = runs;
    }

    public void requireReader(TenantContext.Principal me, long runId) {
        runs.find(runId, me.tenantId()).orElseThrow(
                () -> ProblemException.notFound("run.not_found", "no such run"));
        // `findVisible` is the same query that decides which sessions appear in a trainee's list: if the
        // run is not in it, they do not play in it.
        if ("PLAYER".equals(me.role())
                && runs.findVisible(me.tenantId(), me.userId(), me.role()).stream()
                        .noneMatch(run -> run.id() == runId)) {
            throw ProblemException.forbidden("run.not_yours", "you do not play in this session");
        }
    }
}
