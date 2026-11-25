package com.forge.platform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final DomainEventBus domainEventBus;
    private final ObjectMapper objectMapper;
    private final int maxRetries;
    private final Counter publishedCounter;
    private final Counter failedCounter;

    public OutboxService(
        OutboxEventRepository outboxEventRepository,
        DomainEventBus domainEventBus,
        ObjectMapper objectMapper,
        MeterRegistry meterRegistry,
        @Value("${forge.outbox.max-retries:3}") int maxRetries
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.domainEventBus = domainEventBus;
        this.objectMapper = objectMapper;
        this.maxRetries = maxRetries;
        this.publishedCounter = Counter.builder("forge.outbox.published").register(meterRegistry);
        this.failedCounter = Counter.builder("forge.outbox.failed").register(meterRegistry);
    }

    @Transactional
    public OutboxEvent enqueue(String tenantId, String aggregateType, String aggregateId,
                               String eventType, String topic, Map<String, Object> payloadMap) {
        try {
            String json = objectMapper.writeValueAsString(payloadMap);
            OutboxEvent event = OutboxEvent.pending(tenantId, aggregateType, aggregateId, eventType, topic, json);
            return outboxEventRepository.save(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize outbox payload", ex);
        }
    }

    @Scheduled(fixedDelayString = "${forge.outbox.poll-interval-ms:800}")
    @Transactional
    public void pollAndDispatch() {
        List<OutboxEvent> batch = outboxEventRepository
            .findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc("PENDING", Instant.now(), PageRequest.of(0, 20));
        for (OutboxEvent event : batch) {
            try {
                domainEventBus.publish(event);
                event.markPublished();
                publishedCounter.increment();
            } catch (Exception ex) {
                log.warn("Outbox publish failed for event {} (retry {}): {}", event.getId(), event.getRetryCount(), ex.getMessage());
                event.recordFailure(ex.getMessage(), maxRetries);
                failedCounter.increment();
            }
            outboxEventRepository.save(event);
        }
    }

    public Map<String, Object> stats() {
        return Map.of(
            "transport", domainEventBus.transportName(),
            "pending", outboxEventRepository.countByStatus("PENDING"),
            "published", outboxEventRepository.countByStatus("PUBLISHED"),
            "deadLetter", outboxEventRepository.countByStatus("DEAD_LETTER")
        );
    }
}
