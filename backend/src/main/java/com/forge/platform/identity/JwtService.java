package com.forge.platform.identity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(
        @Value("${forge.security.jwt-secret}") String rawSecret,
        @Value("${forge.security.jwt-expiration-ms:86400000}") long expirationMs
    ) {
        this.signingKey = Keys.hmacShaKeyFor(ensureKeyLength(rawSecret));
        this.expirationMs = expirationMs;
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(user.getId())
            .claim("tenantId", user.getTenantId())
            .claim("email", user.getEmail())
            .claim("role", user.getRole().name())
            .claim("name", user.getFullName())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusMillis(expirationMs)))
            .signWith(signingKey)
            .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    private byte[] ensureKeyLength(String raw) {
        try {
            byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
            if (bytes.length >= 32) {
                return bytes;
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize JWT signing key", e);
        }
    }
}
