package com.forge.platform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.common.CorrelationIdFilter;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
@Profile("!local")
public class KafkaDomainEventBus implements DomainEventBus {

    public static final String HEADER_EVENT_ID = "X-Forge-Event-Id";
    public static final String HEADER_TENANT_ID = "X-Forge-Tenant-Id";
    public static final String HEADER_CORRELATION_ID = CorrelationIdFilter.HEADER_CORRELATION_ID;

    private static final Logger log = LoggerFactory.getLogger(KafkaDomainEventBus.class);
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaDomainEventBus(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(OutboxEvent event) {
        String key = event.getTenantId() + ":" + event.getAggregateId();
        String correlationId = extractCorrelationId(event.getPayload());
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(event.getTopic(), key, event.getPayload());
            record.headers().add(HEADER_EVENT_ID, event.getId().getBytes(StandardCharsets.UTF_8));
            record.headers().add(HEADER_TENANT_ID, event.getTenantId().getBytes(StandardCharsets.UTF_8));
            record.headers().add(HEADER_CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
            log.info("Published outbox event {} to Kafka topic {} (key={}, correlationId={})",
                event.getId(), event.getTopic(), key, correlationId);
        } catch (Exception ex) {
            throw new IllegalStateException("Kafka publish failed for event " + event.getId(), ex);
        }
    }

    private String extractCorrelationId(String payload) {
        try {
            String fromPayload = objectMapper.readTree(payload).path("correlationId").asText("");
            if (!fromPayload.isBlank()) {
                return fromPayload;
            }
        } catch (Exception ignored) {
            // fallback
        }
        return CorrelationIdFilter.currentOrNew();
    }

    @Override
    public String transportName() {
        return "apache-kafka";
    }
}
