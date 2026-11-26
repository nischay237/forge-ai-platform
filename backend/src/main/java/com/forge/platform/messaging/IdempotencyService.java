package com.forge.platform.messaging;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Atomic, lease-based two-phase idempotency guard backed by direct JDBC statements so duplicate key
 * detection never taints a JPA EntityManager or triggers UnexpectedRollbackException.
 *
 * Lifecycle:
 *   1. tryClaimEvent()  -> atomically inserts IN_PROGRESS row with lease_until (or reclaims expired IN_PROGRESS)
 *   2. markCompleted()  -> transitions IN_PROGRESS -> COMPLETED
 *   3. releaseClaim()   -> deletes IN_PROGRESS claim on retryable failure so retries actually execute
 */
@Service
public class IdempotencyService {

    private static final Duration DEFAULT_LEASE = Duration.ofMinutes(5);

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaimEvent(String eventKey, String tenantId, String eventType) {
        return tryClaimEvent(eventKey, tenantId, eventType, DEFAULT_LEASE);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaimEvent(String eventKey, String tenantId, String eventType, Duration leaseDuration) {
        Instant now = Instant.now();
        Instant leaseUntil = now.plus(leaseDuration != null ? leaseDuration : DEFAULT_LEASE);
        try {
            int inserted = jdbcTemplate.update(
                "INSERT INTO processed_events (event_key, tenant_id, event_type, status, lease_until, processed_at) "
                    + "VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?)",
                eventKey,
                tenantId,
                eventType,
                Timestamp.from(leaseUntil),
                Timestamp.from(now)
            );
            if (inserted == 1) {
                return true;
            }
        } catch (DuplicateKeyException duplicate) {
            // Row already exists: attempt atomic takeover ONLY if previous worker crashed and lease expired
            int reclaimed = jdbcTemplate.update(
                "UPDATE processed_events SET lease_until = ?, processed_at = ? "
                    + "WHERE event_key = ? AND status = 'IN_PROGRESS' AND lease_until IS NOT NULL AND lease_until < ?",
                Timestamp.from(leaseUntil),
                Timestamp.from(now),
                eventKey,
                Timestamp.from(now)
            );
            return reclaimed == 1;
        }
        return false;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(String eventKey) {
        jdbcTemplate.update(
            "UPDATE processed_events SET status = 'COMPLETED', lease_until = NULL, processed_at = ? WHERE event_key = ?",
            Timestamp.from(Instant.now()),
            eventKey
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseClaim(String eventKey) {
        jdbcTemplate.update(
            "DELETE FROM processed_events WHERE event_key = ? AND status = 'IN_PROGRESS'",
            eventKey
        );
    }
}
