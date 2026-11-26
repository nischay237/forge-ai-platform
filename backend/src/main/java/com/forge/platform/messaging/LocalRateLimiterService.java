package com.forge.platform.messaging;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Profile("local")
public class LocalRateLimiterService implements RateLimiterService {

    private static final int MAX_TRACKED_KEYS = 20_000;

    private final ConcurrentHashMap<String, Deque<Instant>> windows = new ConcurrentHashMap<>();
    private final AtomicLong callCounter = new AtomicLong();

    @Override
    public boolean allowRequest(String key, int maxRequests, Duration window) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(window);
        if ((callCounter.incrementAndGet() & 255) == 0 || windows.size() > MAX_TRACKED_KEYS) {
            pruneIdleEntries(now.minus(Duration.ofHours(1)));
        }
        Deque<Instant> timestamps = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxRequests) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    private void pruneIdleEntries(Instant staleThreshold) {
        windows.entrySet().removeIf(entry -> {
            Deque<Instant> dq = entry.getValue();
            synchronized (dq) {
                return dq.isEmpty() || dq.peekLast().isBefore(staleThreshold);
            }
        });
    }
}
