package com.forge.platform.identity;

import com.forge.platform.messaging.ForgeCacheService;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Distributed JWT JTI revocation denylist backed by Redis in production and bounded TTL cache in local mode.
 */
@Service
public class TokenRevocationService {

    private static final String KEY_PREFIX = "jwt:revoked:";
    private final ForgeCacheService cacheService;

    public TokenRevocationService(ForgeCacheService cacheService) {
        this.cacheService = cacheService;
    }

    public void revokeJti(String jti, Duration ttl) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        Duration safeTtl = (ttl == null || ttl.isNegative() || ttl.isZero()) ? Duration.ofMinutes(15) : ttl;
        cacheService.put(KEY_PREFIX + jti, "1", safeTtl);
    }

    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return cacheService.get(KEY_PREFIX + jti).isPresent();
    }
}
