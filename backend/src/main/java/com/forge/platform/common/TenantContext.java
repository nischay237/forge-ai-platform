package com.forge.platform.common;

public final class TenantContext {

    public record PrincipalContext(
        String userId,
        String tenantId,
        String email,
        String role,
        String fullName
    ) {}

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
        return ctx != null ? ctx.email() : "system@forge.dev";
    }

    public static String requireRole() {
        PrincipalContext ctx = CURRENT.get();
        return ctx != null ? ctx.role() : "SYSTEM";
    }

    public static void clear() {
        CURRENT.remove();
    }
}
