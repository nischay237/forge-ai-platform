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
import java.util.Optional;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final TokenRevocationService tokenRevocationService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(
        JwtService jwtService,
        TokenRevocationService tokenRevocationService,
        UserRepository userRepository
    ) {
        this.jwtService = jwtService;
        this.tokenRevocationService = tokenRevocationService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        try {
            String token = extractBearerToken(request);
            if (token != null) {
                authenticateFromToken(token, request.getRequestURI());
            }
            // Execute filter chain strictly ONCE outside JWT parsing try/catch
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void authenticateFromToken(String token, String uri) {
        try {
            Claims claims = jwtService.parseToken(token);
            String jti = claims.getId();
            if (jti != null && tokenRevocationService.isRevoked(jti)) {
                log.debug("Rejected revoked JWT jti for {}", uri);
                SecurityContextHolder.clearContext();
                TenantContext.clear();
                return;
            }

            String userId = claims.getSubject();
            String tenantId = claims.get("tenantId", String.class);
            Integer tokenVersion = claims.get("ver", Integer.class);
            if (userId == null || tenantId == null) {
                return;
            }

            // Revalidate account active status, current role, and token version against DB
            Optional<User> userOpt = userRepository.findByIdAndTenantId(userId, tenantId);
            if (userOpt.isEmpty()) {
                return;
            }
            User user = userOpt.get();
            if (!user.isActive() || user.isLocked()) {
                return;
            }
            if (tokenVersion != null && tokenVersion != user.getTokenVersion()) {
                return;
            }

            TenantContext.PrincipalContext principal = new TenantContext.PrincipalContext(
                user.getId(),
                user.getTenantId(),
                user.getEmail(),
                user.getRole().name(),
                user.getFullName(),
                jti
            );
            TenantContext.set(principal);
            var auth = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (Exception ex) {
            log.debug("JWT verification rejected request to {}: {}", uri, ex.getMessage());
            SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String raw = header.substring(7).trim();
            return raw.isEmpty() ? null : raw;
        }
        return null;
    }
}
