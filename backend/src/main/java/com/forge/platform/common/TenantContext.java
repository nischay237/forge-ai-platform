package com.forge.platform.common;

public final class TenantContext {

    public record PrincipalContext(
        String userId,
        String tenantId,
        String email,
        String role,
        String fullName,
        String tokenJti
    ) {
        public PrincipalContext(String userId, String tenantId, String email, String role, String fullName) {
            this(userId, tenantId, email, role, fullName, null);
        }

        public static PrincipalContext system(String tenantId, String workerEmail) {
            return new PrincipalContext("system", tenantId, workerEmail, "SYSTEM", "FORGE Worker", null);
        }
    }

    private static final ThreadLocal<PrincipalContext> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(PrincipalContext context) {
        CURRENT.set(context);
    }

    public static PrincipalContext get() {
        return CURRENT.get();
    }

    public static String requireTenantId() {
        PrincipalContext ctx = CURRENT.get();
        if (ctx == null || ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw new ForbiddenOperationException("Missing authenticated tenant context");
        }
        return ctx.tenantId();
    }

    public static String requireEmail() {
        PrincipalContext ctx = CURRENT.get();
        if (ctx == null || ctx.email() == null || ctx.email().isBlank()) {
            throw new ForbiddenOperationException("Missing authenticated actor email in tenant context");
        }
        return ctx.email();
    }

    public static String requireRole() {
        PrincipalContext ctx = CURRENT.get();
        if (ctx == null || ctx.role() == null || ctx.role().isBlank()) {
            throw new ForbiddenOperationException("Missing authenticated actor role in tenant context");
        }
        return ctx.role();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
