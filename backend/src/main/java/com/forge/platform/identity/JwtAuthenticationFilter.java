package com.forge.platform.identity;

import com.forge.platform.common.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        try {
            String token = extractToken(request);
            if (token != null) {
                Claims claims = jwtService.parseToken(token);
                String userId = claims.getSubject();
                String tenantId = claims.get("tenantId", String.class);
                String email = claims.get("email", String.class);
                String role = claims.get("role", String.class);
                String name = claims.get("name", String.class);

                if (userId != null && tenantId != null && role != null) {
                    TenantContext.PrincipalContext principal = new TenantContext.PrincipalContext(
                        userId, tenantId, email, role, name
                    );
                    TenantContext.set(principal);
                    var auth = new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
            filterChain.doFilter(request, response);
        } catch (Exception ex) {
            log.debug("JWT verification rejected request to {}: {}", request.getRequestURI(), ex.getMessage());
            SecurityContextHolder.clearContext();
            TenantContext.clear();
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        // Native browser EventSource cannot attach Authorization headers, so SSE streams accept ?token=
        if (request.getRequestURI().startsWith("/api/v1/stream")) {
            String queryToken = request.getParameter("token");
            if (queryToken != null && !queryToken.isBlank()) {
                return queryToken.trim();
            }
        }
        return null;
    }
}
