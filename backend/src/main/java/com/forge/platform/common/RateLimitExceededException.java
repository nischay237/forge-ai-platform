package com.forge.platform.common;

import org.springframework.http.HttpStatus;

public class RateLimitExceededException extends ForgeException {
    public RateLimitExceededException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", message);
    }
}
