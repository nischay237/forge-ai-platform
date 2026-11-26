package com.forge.platform.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.audit.AuditService;
import com.forge.platform.common.CorrelationIdFilter;
import com.forge.platform.common.TenantContext;
import com.forge.platform.sse.PipelineEvent;
import com.forge.platform.sse.SseHubService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@Profile("!local")
public class KafkaDomainEventListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaDomainEventListener.class);
    private final ObjectProvider<DomainEventHandler> handlersProvider;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final SseHubService sseHubService;
    private final Counter dltCounter;

    public KafkaDomainEventListener(
        ObjectProvider<DomainEventHandler> handlersProvider,
        ObjectMapper objectMapper,
        AuditService auditService,
        SseHubService sseHubService,
        MeterRegistry meterRegistry
    ) {
        this.handlersProvider = handlersProvider;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.sseHubService = sseHubService;
        this.dltCounter = Counter.builder("forge.kafka.dlt.events")
            .description("Kafka domain events routed to dead-letter topics")
            .register(meterRegistry);
    }

    @RetryableTopic(
        attempts = "3",
        backoff = @Backoff(delay = 1000, multiplier = 2.0),
        dltTopicSuffix = ".dlt"
    )
    @KafkaListener(topics = {
        ForgeTopics.PR_REVIEW_REQUESTED,
        ForgeTopics.REPO_INDEX_REQUESTED,
        ForgeTopics.PR_PREDICTION_REQUESTED
    }, groupId = "forge-workers")
    public void onMessage(
        String payload,
        @Header(KafkaHeaders.RECEIVED_TOPIC) String receivedTopic,
        @Header(name = KafkaHeaders.ORIGINAL_TOPIC, required = false) String originalTopic,
        @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
        @Header(name = KafkaDomainEventBus.HEADER_EVENT_ID, required = false) byte[] eventIdHeader,
        @Header(name = KafkaDomainEventBus.HEADER_TENANT_ID, required = false) byte[] tenantIdHeader,
        @Header(name = KafkaDomainEventBus.HEADER_CORRELATION_ID, required = false) byte[] correlationIdHeader
    ) throws Exception {
        String logicalTopic = (originalTopic != null && !originalTopic.isBlank()) ? originalTopic : receivedTopic;
        JsonNode node = objectMapper.readTree(payload);

        String headerEventId = eventIdHeader != null ? new String(eventIdHeader, StandardCharsets.UTF_8) : "";
        String headerTenantId = tenantIdHeader != null ? new String(tenantIdHeader, StandardCharsets.UTF_8) : "";
        String headerCorrId = correlationIdHeader != null ? new String(correlationIdHeader, StandardCharsets.UTF_8) : "";

        String eventId = !headerEventId.isBlank()
            ? headerEventId
            : node.path("eventId").asText(key != null ? key : String.valueOf(payload.hashCode()));
        String tenantId = !headerTenantId.isBlank() ? headerTenantId : node.path("tenantId").asText("");
        String correlationId = !headerCorrId.isBlank()
            ? headerCorrId
            : node.path("correlationId").asText(CorrelationIdFilter.currentOrNew());

        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Missing tenantId on Kafka event " + eventId + " for topic " + logicalTopic);
        }

        List<DomainEventHandler> matching = handlersProvider.orderedStream()
            .filter(h -> h.supports(logicalTopic))
            .toList();
        if (matching.isEmpty()) {
            throw new IllegalStateException("No DomainEventHandler registered for logical topic " + logicalTopic
                + " (receivedTopic=" + receivedTopic + ")");
        }

        TenantContext.set(TenantContext.PrincipalContext.system(tenantId, "kafka-worker@forge.dev"));
        MDC.put(CorrelationIdFilter.MDC_CORRELATION_ID, correlationId);
        MDC.put(CorrelationIdFilter.MDC_TENANT_ID, tenantId);
        try {
            for (DomainEventHandler handler : matching) {
                handler.handle(eventId, tenantId, payload);
            }
        } finally {
            TenantContext.clear();
            MDC.remove(CorrelationIdFilter.MDC_CORRELATION_ID);
            MDC.remove(CorrelationIdFilter.MDC_TENANT_ID);
        }
    }

    @DltHandler
    public void onDeadLetter(
        String payload,
        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
        @Header(name = KafkaHeaders.ORIGINAL_TOPIC, required = false) String originalTopic
    ) {
        dltCounter.increment();
        log.error("Event routed to Dead-Letter Topic {} (original={})", topic, originalTopic);
        try {
            JsonNode node = objectMapper.readTree(payload);
            String tenantId = node.path("tenantId").asText("");
            String reviewId = node.path("reviewId").asText("");
            String repositoryId = node.path("repositoryId").asText("");
            String resourceId = !reviewId.isBlank() ? reviewId : (!repositoryId.isBlank() ? repositoryId : "unknown");
            String eventType = resolveEventType(originalTopic, topic, reviewId);

            if (!tenantId.isBlank()) {
                auditService.recordIsolated(tenantId, "kafka-dlt@forge.dev", "SYSTEM",
                    "EVENT_DEAD_LETTERED", "OUTBOX_EVENT", resourceId,
                    "Event routed to DLT topic " + topic + " after exhausting retries", "kafka");
                sseHubService.publish(tenantId, PipelineEvent.of(
                    eventType,
                    resourceId,
                    "FAILED",
                    100,
                    "Background pipeline job exhausted retries and was routed to dead-letter queue"
                ));
            }
        } catch (Exception ex) {
            log.error("Failed to record DLT audit metadata for topic {}: {}", topic, ex.getMessage());
        }
    }

    private String resolveEventType(String originalTopic, String receivedTopic, String reviewId) {
        String candidate = (originalTopic != null && !originalTopic.isBlank()) ? originalTopic : receivedTopic;
        if (candidate != null && candidate.contains("pr-prediction")) {
            return "PR_PREDICTION";
        }
        if (candidate != null && candidate.contains("repo-index")) {
            return "REPO_INDEX";
        }
        return !reviewId.isBlank() ? "PR_REVIEW" : "REPO_INDEX";
    }
}
