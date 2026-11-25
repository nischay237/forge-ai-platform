package com.forge.platform.messaging;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Profile("!local")
public class RedisRateLimiterService implements RateLimiterService {

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiterService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean allowRequest(String key, int maxRequests, Duration window) {
        String redisKey = "forge:rl:" + key;
        Long count = redisTemplate.opsForValue().increment(redisKey);
        if (count != null && count == 1L) {
            redisTemplate.expire(redisKey, window);
        }
        return count != null && count <= maxRequests;
    }
}
