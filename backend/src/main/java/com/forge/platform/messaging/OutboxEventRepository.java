package com.forge.platform.messaging;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    @Query("""
        SELECT e.id FROM OutboxEvent e
        WHERE (e.status = 'PENDING' AND e.nextAttemptAt <= :now)
           OR (e.status = 'PROCESSING' AND e.lockedUntil IS NOT NULL AND e.lockedUntil <= :now)
        ORDER BY e.createdAt ASC
        """)
    List<String> findClaimableEventIds(@Param("now") Instant now, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE OutboxEvent e
        SET e.status = 'PROCESSING', e.lockedBy = :workerId, e.lockedUntil = :lockedUntil
        WHERE e.id = :id
          AND (
            (e.status = 'PENDING' AND e.nextAttemptAt <= :now)
            OR (e.status = 'PROCESSING' AND e.lockedUntil IS NOT NULL AND e.lockedUntil <= :now)
          )
        """)
    int tryClaimEvent(
        @Param("id") String id,
        @Param("workerId") String workerId,
        @Param("lockedUntil") Instant lockedUntil,
        @Param("now") Instant now
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE OutboxEvent e
        SET e.status = 'PENDING', e.retryCount = 0, e.nextAttemptAt = :now, e.lockedBy = NULL, e.lockedUntil = NULL
        WHERE e.tenantId = :tenantId AND e.status = 'DEAD_LETTER'
        """)
    int requeueDeadLetterForTenant(@Param("tenantId") String tenantId, @Param("now") Instant now);

    @Query("SELECT MIN(e.createdAt) FROM OutboxEvent e WHERE e.status = 'PENDING'")
    Optional<Instant> findOldestPendingCreatedAt();

    long countByStatus(String status);
    long countByTenantIdAndStatus(String tenantId, String status);
}
