package com.forge.platform.messaging;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    @Query("""
        SELECT e FROM OutboxEvent e
        WHERE (e.status = 'PENDING' AND e.nextAttemptAt <= :now)
           OR (e.status = 'PROCESSING' AND e.lockedUntil IS NOT NULL AND e.lockedUntil <= :now)
        ORDER BY e.createdAt ASC
        """)
    List<OutboxEvent> findClaimableEvents(@Param("now") Instant now, Pageable pageable);

    long countByStatus(String status);
    long countByTenantIdAndStatus(String tenantId, String status);
}
