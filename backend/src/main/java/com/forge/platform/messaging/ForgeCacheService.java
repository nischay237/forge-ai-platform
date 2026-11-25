package com.forge.platform.messaging;

import java.time.Duration;
import java.util.Optional;

public interface ForgeCacheService {
    void put(String key, String value, Duration ttl);
    Optional<String> get(String key);
    void evict(String key);
    String backendName();
}
