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

    private static final int MAX_ENTRIES = 10_000;

    private record Entry(String value, Instant expiresAt) {}

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    @Override
    public void put(String key, String value, Duration ttl) {
        if (store.size() >= MAX_ENTRIES) {
            Instant now = Instant.now();
            store.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
        }
        store.put(key, new Entry(value, Instant.now().plus(ttl)));
    }

    @Override
    public Optional<String> get(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            store.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public Optional<String> consumeOnce(String key) {
        Entry entry = store.remove(key);
        if (entry == null || Instant.now().isAfter(entry.expiresAt())) {
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
