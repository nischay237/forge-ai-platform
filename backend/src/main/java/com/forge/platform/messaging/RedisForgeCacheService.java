package com.forge.platform.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Production Redis cache adapter with explicit outage policy:
 * - Standard cache read/write/evict degrade safely with warning logs and Micrometer error metrics
 *   so transient Redis unavailability does not break core API workflows.
 * - Single-use security token redemption (`consumeOnce`, used by SSE tickets) fails closed
 *   (returns Optional.empty()) to prevent replay or authorization bypass during outages.
 */
@Component
@Profile("!local")
public class RedisForgeCacheService implements ForgeCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisForgeCacheService.class);

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    public RedisForgeCacheService(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set("forge:" + key, value, ttl);
        } catch (Exception ex) {
            recordError("put");
            log.warn("Redis cache put failed for key prefix {} (continuing without cache): {}",
                safeKeyPrefix(key), ex.getMessage());
        }
    }

    @Override
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get("forge:" + key));
        } catch (Exception ex) {
            recordError("get");
            log.warn("Redis cache get failed for key prefix {} (treating as cache miss): {}",
                safeKeyPrefix(key), ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> consumeOnce(String key) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete("forge:" + key));
        } catch (Exception ex) {
            recordError("consume_once");
            log.error("Redis atomic consumeOnce failed for key prefix {} (failing closed): {}",
                safeKeyPrefix(key), ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void evict(String key) {
        try {
            redisTemplate.delete("forge:" + key);
        } catch (Exception ex) {
            recordError("evict");
            log.warn("Redis cache evict failed for key prefix {}: {}", safeKeyPrefix(key), ex.getMessage());
        }
    }

    @Override
    public String backendName() {
        return "redis";
    }

    private void recordError(String operation) {
        meterRegistry.counter("forge.redis.errors", "operation", operation).increment();
    }

    private String safeKeyPrefix(String key) {
        if (key == null) return "unknown";
        int idx = key.indexOf(':');
        return idx > 0 ? key.substring(0, idx) : "general";
    }
}
