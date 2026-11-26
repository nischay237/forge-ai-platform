package com.forge.platform.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;

/**
 * Atomic Redis rate limiter using a single Lua script so INCR + PEXPIRE cannot leave immortal keys.
 * Fails open with a warning if Redis is transiently unreachable so availability is preserved.
 */
@Component
@Profile("!local")
public class RedisRateLimiterService implements RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiterService.class);

    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL_SCRIPT = new DefaultRedisScript<>(
        """
        local current = redis.call('INCR', KEYS[1])
        if tonumber(current) == 1 then
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
        end
        return current
        """,
        Long.class
    );

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiterService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean allowRequest(String key, int maxRequests, Duration window) {
        String redisKey = "forge:rl:" + key;
        try {
            Long count = redisTemplate.execute(
                INCREMENT_WITH_TTL_SCRIPT,
                Collections.singletonList(redisKey),
                String.valueOf(Math.max(1000L, window.toMillis()))
            );
            return count == null || count <= maxRequests;
        } catch (Exception ex) {
            log.warn("Redis rate limiter unavailable for key {} (failing open): {}", key, ex.getMessage());
            return true;
        }
    }
}
