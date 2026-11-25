package com.forge.platform.messaging;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("local")
public class LocalForgeCacheService implements ForgeCacheService {

    private record Entry(String value, Instant expiresAt) {}

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    @Override
    public void put(String key, String value, Duration ttl) {
        store.put(key, new Entry(value, Instant.now().plus(ttl)));
    }

    @Override
    public Optional<String> get(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            store.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void evict(String key) {
        store.remove(key);
    }

    @Override
    public String backendName() {
        return "local-ttl-cache";
    }
}
