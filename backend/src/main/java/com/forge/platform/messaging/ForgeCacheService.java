package com.forge.platform.messaging;

import java.time.Duration;
import java.util.Optional;

public interface ForgeCacheService {
    void put(String key, String value, Duration ttl);
    Optional<String> get(String key);
    /**
     * Atomically retrieves and removes the key if present and unexpired.
     * Used for single-use SSE tickets and replay-resistant tokens.
     */
    Optional<String> consumeOnce(String key);
    void evict(String key);
    String backendName();
}
