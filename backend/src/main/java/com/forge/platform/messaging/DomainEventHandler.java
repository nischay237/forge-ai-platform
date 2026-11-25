package com.forge.platform.messaging;

public interface DomainEventHandler {
    boolean supports(String topic);
    void handle(String eventId, String tenantId, String payload);
}
