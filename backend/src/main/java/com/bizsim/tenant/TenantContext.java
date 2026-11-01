package com.bizsim.tenant;

/**
 * The authenticated caller for the current request.
 *
 * <p>Every data access path takes the tenant from here, never from a request parameter. That is the
 * single rule that makes the multi-tenant decision (brief §4.1) hold: a client cannot ask for
 * another tenant's row by changing a URL, because no endpoint accepts a tenant argument.
 */
public final class TenantContext {

    public record Principal(long userId, long tenantId, String tenantCode, String role) {
    }

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Principal principal) {
        CURRENT.set(principal);
    }

    public static Principal require() {
        Principal p = CURRENT.get();
        if (p == null) {
            throw new IllegalStateException("No authenticated principal on this thread");
        }
        return p;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
