package com.forge.platform.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Component
@Profile("!local")
public class KafkaDomainEventListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaDomainEventListener.class);
    private final ObjectProvider<DomainEventHandler> handlersProvider;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public KafkaDomainEventListener(ObjectProvider<DomainEventHandler> handlersProvider, ObjectMapper objectMapper, AuditService auditService) {
        this.handlersProvider = handlersProvider;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @RetryableTopic(
        attempts = "3",
        backoff = @Backoff(delay = 1000, multiplier = 2.0),
        dltTopicSuffix = ".dlt"
    )
    @KafkaListener(topics = {ForgeTopics.PR_REVIEW_REQUESTED, ForgeTopics.REPO_INDEX_REQUESTED}, groupId = "forge-workers")
    public void onMessage(
        String payload,
        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
        @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key
    ) throws Exception {
        JsonNode node = objectMapper.readTree(payload);
        String eventId = node.path("eventId").asText(key != null ? key : String.valueOf(payload.hashCode()));
        String tenantId = node.path("tenantId").asText("");
        handlersProvider.orderedStream().forEach(handler -> {
            if (handler.supports(topic)) {
                handler.handle(eventId, tenantId, payload);
            }
        });
    }

    @DltHandler
    public void onDeadLetter(String payload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("Event routed to Dead-Letter Topic {}: {}", topic, payload);
        try {
            JsonNode node = objectMapper.readTree(payload);
            String tenantId = node.path("tenantId").asText("system");
            String resourceId = node.path("reviewId").asText(node.path("repositoryId").asText("unknown"));
            auditService.record(tenantId, "kafka-dlt@forge.dev", "SYSTEM",
                "EVENT_DEAD_LETTERED", "OUTBOX_EVENT", resourceId,
                "Event routed to DLT topic " + topic, "kafka");
        } catch (Exception ignored) {
            // logged above
        }
    }
}
