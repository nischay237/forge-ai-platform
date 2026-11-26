package com.forge.platform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.common.CorrelationIdFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional Outbox dispatcher using atomic conditional per-row claiming -> publish-outside-TX -> finalize
 * so multiple application replicas never double-claim the same outbox row, database connections are never
 * held across external Kafka broker I/O, and crashed claims automatically expire via lease recovery.
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final DomainEventBus domainEventBus;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final int maxRetries;
    private final Duration leaseDuration;
    private final String workerInstanceId;
    private final Counter publishedCounter;
    private final Counter failedCounter;

    public OutboxService(
        OutboxEventRepository outboxEventRepository,
        DomainEventBus domainEventBus,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        MeterRegistry meterRegistry,
        @Value("${forge.outbox.max-retries:3}") int maxRetries,
        @Value("${forge.outbox.lease-seconds:60}") int leaseSeconds
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.domainEventBus = domainEventBus;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxRetries = maxRetries;
        this.leaseDuration = Duration.ofSeconds(Math.max(15, leaseSeconds));
        this.workerInstanceId = "forge-node-" + UUID.randomUUID().toString().substring(0, 8);
        this.publishedCounter = Counter.builder("forge.outbox.published").register(meterRegistry);
        this.failedCounter = Counter.builder("forge.outbox.failed").register(meterRegistry);

        Gauge.builder("forge.outbox.pending", outboxEventRepository, repo -> safeCount(repo, "PENDING"))
            .description("Pending outbox events awaiting publication")
            .register(meterRegistry);
        Gauge.builder("forge.outbox.dead_letter", outboxEventRepository, repo -> safeCount(repo, "DEAD_LETTER"))
            .description("Dead-lettered outbox events requiring operator inspection")
            .register(meterRegistry);
        Gauge.builder("forge.outbox.oldest_pending_age_seconds", this, OutboxService::oldestPendingAgeSeconds)
            .description("Age in seconds of the oldest pending outbox event")
            .register(meterRegistry);
    }

    @Transactional
    public OutboxEvent enqueue(String tenantId, String aggregateType, String aggregateId,
                               String eventType, String topic, Map<String, Object> payloadMap) {
        try {
            Map<String, Object> enriched = new LinkedHashMap<>(payloadMap);
            enriched.putIfAbsent("correlationId", CorrelationIdFilter.currentOrNew());
            String json = objectMapper.writeValueAsString(enriched);
            OutboxEvent event = OutboxEvent.pending(tenantId, aggregateType, aggregateId, eventType, topic, json);
            return outboxEventRepository.save(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize outbox payload", ex);
        }
    }

    /**
     * Intentionally NOT @Transactional so DB connections are held only for brief atomic claim/finalize phases
     * and never across Kafka network calls.
     */
    @Scheduled(fixedDelayString = "${forge.outbox.poll-interval-ms:800}")
    public void pollAndDispatch() {
        List<OutboxEvent> claimed = claimBatch(workerInstanceId);
        for (OutboxEvent event : claimed) {
            try {
                domainEventBus.publish(event);
                finalizePublished(event.getId());
                publishedCounter.increment();
            } catch (Exception ex) {
                log.warn("Outbox publish failed for event {} (retry {}): {}", event.getId(), event.getRetryCount(), ex.getMessage());
                finalizeFailed(event.getId(), ex.getMessage());
                failedCounter.increment();
            }
        }
    }

    /**
     * Atomically claims eligible PENDING or expired-lease PROCESSING events for a given worker replica.
     * Package-private so multi-replica concurrency tests can verify zero duplicate claims.
     */
    List<OutboxEvent> claimBatch(String claimantWorkerId) {
        List<OutboxEvent> result = transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            Instant lockedUntil = now.plus(leaseDuration);
            List<String> candidateIds = outboxEventRepository.findClaimableEventIds(now, PageRequest.of(0, 20));
            if (candidateIds.isEmpty()) {
                return List.<OutboxEvent>of();
            }
            List<String> wonIds = new ArrayList<>(candidateIds.size());
            for (String id : candidateIds) {
                if (outboxEventRepository.tryClaimEvent(id, claimantWorkerId, lockedUntil, now) == 1) {
                    wonIds.add(id);
                }
            }
            return wonIds.isEmpty() ? List.<OutboxEvent>of() : outboxEventRepository.findAllById(wonIds);
        });
        return result != null ? result : List.of();
    }

    @Transactional
    public int requeueDeadLetterEvents(String tenantId) {
        return outboxEventRepository.requeueDeadLetterForTenant(tenantId, Instant.now());
    }

    private void finalizePublished(String eventId) {
        transactionTemplate.executeWithoutResult(status ->
            outboxEventRepository.findById(eventId).ifPresent(event -> {
                event.markPublished();
                outboxEventRepository.save(event);
            })
        );
    }

    private void finalizeFailed(String eventId, String errorMessage) {
        transactionTemplate.executeWithoutResult(status ->
            outboxEventRepository.findById(eventId).ifPresent(event -> {
                event.recordFailure(errorMessage, maxRetries);
                outboxEventRepository.save(event);
            })
        );
    }

    private double safeCount(OutboxEventRepository repo, String status) {
        try {
            return repo.countByStatus(status);
        } catch (Exception ex) {
            return 0.0;
        }
    }

    private double oldestPendingAgeSeconds() {
        try {
            return outboxEventRepository.findOldestPendingCreatedAt()
                .map(created -> Math.max(0.0, Duration.between(created, Instant.now()).toMillis() / 1000.0))
                .orElse(0.0);
        } catch (Exception ex) {
            return 0.0;
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> statsForTenant(String tenantId) {
        return Map.of(
            "transport", domainEventBus.transportName(),
            "pending", outboxEventRepository.countByTenantIdAndStatus(tenantId, "PENDING"),
            "processing", outboxEventRepository.countByTenantIdAndStatus(tenantId, "PROCESSING"),
            "published", outboxEventRepository.countByTenantIdAndStatus(tenantId, "PUBLISHED"),
            "deadLetter", outboxEventRepository.countByTenantIdAndStatus(tenantId, "DEAD_LETTER")
        );
    }
}
