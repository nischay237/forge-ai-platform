package com.forge.platform.messaging;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    List<OutboxEvent> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
        String status, Instant now, Pageable pageable
    );
    long countByStatus(String status);
}
