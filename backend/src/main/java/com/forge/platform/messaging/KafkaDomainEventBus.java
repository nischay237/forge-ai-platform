package com.forge.platform.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Profile("!local")
public class KafkaDomainEventBus implements DomainEventBus {

    private static final Logger log = LoggerFactory.getLogger(KafkaDomainEventBus.class);
    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaDomainEventBus(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(OutboxEvent event) {
        String key = event.getTenantId() + ":" + event.getAggregateId();
        try {
            kafkaTemplate.send(event.getTopic(), key, event.getPayload()).get(10, TimeUnit.SECONDS);
            log.info("Published outbox event {} to Kafka topic {} (key={})", event.getId(), event.getTopic(), key);
        } catch (Exception ex) {
            throw new IllegalStateException("Kafka publish failed for event " + event.getId(), ex);
        }
    }

    @Override
    public String transportName() {
        return "apache-kafka";
    }
}
