package com.forge.platform.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Propagates or generates a sanitized X-Correlation-Id header on every HTTP request,
 * binds it to SLF4J MDC for structured log correlation, and guarantees cleanup on exit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_TENANT_ID = "tenantId";

    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{4,64}$");

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER_CORRELATION_ID);
        String correlationId = (incoming != null && SAFE_ID_PATTERN.matcher(incoming.trim()).matches())
            ? incoming.trim()
            : UUID.randomUUID().toString();

        MDC.put(MDC_CORRELATION_ID, correlationId);
        response.setHeader(HEADER_CORRELATION_ID, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_CORRELATION_ID);
            MDC.remove(MDC_TENANT_ID);
        }
    }

    public static String currentOrNew() {
        String existing = MDC.get(MDC_CORRELATION_ID);
        return (existing != null && !existing.isBlank()) ? existing : UUID.randomUUID().toString();
    }
}
