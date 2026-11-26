package com.forge.platform.sse;

import com.forge.platform.common.ForgeException;
import com.forge.platform.common.SecretCryptoService;
import com.forge.platform.messaging.ForgeCacheService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Issues and atomically consumes short-lived, single-use SSE stream tickets so long-lived
 * JWT access tokens never appear in URLs, browser history, or access logs.
 */
@Service
public class SseTicketService {

    private static final String KEY_PREFIX = "sse:ticket:";

    private final ForgeCacheService cacheService;
    private final SecretCryptoService cryptoService;
    private final int ticketTtlSeconds;

    public SseTicketService(
        ForgeCacheService cacheService,
        SecretCryptoService cryptoService,
        @Value("${forge.security.sse-ticket-ttl-seconds:30}") int ticketTtlSeconds
    ) {
        this.cacheService = cacheService;
        this.cryptoService = cryptoService;
        this.ticketTtlSeconds = Math.max(10, Math.min(120, ticketTtlSeconds));
    }

    public record TicketResponse(String ticket, int expiresInSeconds) {}

    public TicketResponse issueTicket(String tenantId) {
        String rawTicket = "sse_" + cryptoService.generateSecureToken(32);
        String hashedKey = KEY_PREFIX + cryptoService.sha256Hex(rawTicket);
        cacheService.put(hashedKey, tenantId, Duration.ofSeconds(ticketTtlSeconds));
        return new TicketResponse(rawTicket, ticketTtlSeconds);
    }

    /**
     * Atomically validates and consumes the ticket exactly once.
     * Throws HTTP 401 if missing, expired, or already replayed.
     */
    public String consumeTicket(String rawTicket) {
        if (rawTicket == null || rawTicket.isBlank()) {
            throw new ForgeException(HttpStatus.UNAUTHORIZED, "INVALID_SSE_TICKET",
                "Missing single-use SSE stream ticket. Request one via POST /api/v1/stream/ticket.");
        }
        String hashedKey = KEY_PREFIX + cryptoService.sha256Hex(rawTicket.trim());
        return cacheService.consumeOnce(hashedKey)
            .orElseThrow(() -> new ForgeException(HttpStatus.UNAUTHORIZED, "INVALID_SSE_TICKET",
                "SSE ticket is invalid, expired, or already consumed."));
    }
}
