package com.forge.platform.messaging;

public interface DomainEventBus {
    void publish(OutboxEvent event);
    String transportName();
}
