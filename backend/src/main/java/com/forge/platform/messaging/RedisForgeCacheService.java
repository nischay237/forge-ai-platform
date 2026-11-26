package com.forge.platform.messaging;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@Profile("!local")
public class RedisForgeCacheService implements ForgeCacheService {

    private final StringRedisTemplate redisTemplate;

    public RedisForgeCacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        redisTemplate.opsForValue().set("forge:" + key, value, ttl);
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redisTemplate.opsForValue().get("forge:" + key));
    }

    @Override
    public Optional<String> consumeOnce(String key) {
        return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete("forge:" + key));
    }

    @Override
    public void evict(String key) {
        redisTemplate.delete("forge:" + key);
    }

    @Override
    public String backendName() {
        return "redis";
    }
}
