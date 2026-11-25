package com.forge.platform.messaging;

import java.time.Duration;

public interface RateLimiterService {
    boolean allowRequest(String key, int maxRequests, Duration window);
}
