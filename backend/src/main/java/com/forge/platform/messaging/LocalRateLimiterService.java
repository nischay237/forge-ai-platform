package com.forge.platform.messaging;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("local")
public class LocalRateLimiterService implements RateLimiterService {

    private final ConcurrentHashMap<String, Deque<Instant>> windows = new ConcurrentHashMap<>();

    @Override
    public boolean allowRequest(String key, int maxRequests, Duration window) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(window);
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
}
