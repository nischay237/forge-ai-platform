package com.forge.platform.identity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    public static final String ISSUER = "forge-platform";
    public static final String AUDIENCE = "forge-api";

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(
        @Value("${forge.security.jwt-secret}") String rawSecret,
        @Value("${forge.security.jwt-expiration-ms:900000}") long expirationMs
    ) {
        if (rawSecret == null || rawSecret.isBlank()) {
            throw new IllegalArgumentException("forge.security.jwt-secret must be configured");
        }
        byte[] secretBytes = rawSecret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalArgumentException("forge.security.jwt-secret must be at least 32 bytes (256 bits) for HS256");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
        this.expirationMs = expirationMs;
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .issuer(ISSUER)
            .audience().add(AUDIENCE).and()
            .subject(user.getId())
            .claim("tenantId", user.getTenantId())
            .claim("email", user.getEmail())
            .claim("role", user.getRole().name())
            .claim("name", user.getFullName())
            .claim("ver", user.getTokenVersion())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusMillis(expirationMs)))
            .signWith(signingKey)
            .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
            .verifyWith(signingKey)
            .requireIssuer(ISSUER)
            .requireAudience(AUDIENCE)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public long getExpirationMs() {
        return expirationMs;
    }
}
