package com.forge.platform.messaging;

import com.forge.platform.common.TenantContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Profile("local")
public class LocalDomainEventBus implements DomainEventBus {

    private static final Logger log = LoggerFactory.getLogger(LocalDomainEventBus.class);
    private final ObjectProvider<DomainEventHandler> handlersProvider;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public LocalDomainEventBus(ObjectProvider<DomainEventHandler> handlersProvider) {
        this.handlersProvider = handlersProvider;
    }

    @Override
    public void publish(OutboxEvent event) {
        executor.submit(() -> {
            TenantContext.set(TenantContext.PrincipalContext.system(event.getTenantId(), "local-worker@forge.dev"));
            try {
                handlersProvider.orderedStream().forEach(handler -> {
                    if (handler.supports(event.getTopic())) {
                        try {
                            handler.handle(event.getId(), event.getTenantId(), event.getPayload());
                        } catch (Exception ex) {
                            log.error("Local async handler failed for topic {} event {}: {}",
                                event.getTopic(), event.getId(), ex.getMessage(), ex);
                        }
                    }
                });
            } finally {
                TenantContext.clear();
            }
        });
    }

    @Override
    public String transportName() {
        return "local-virtual-thread-outbox";
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
