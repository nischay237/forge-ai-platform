package com.forge.platform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.common.CorrelationIdFilter;
import com.forge.platform.common.TenantContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public LocalDomainEventBus(ObjectProvider<DomainEventHandler> handlersProvider, ObjectMapper objectMapper) {
        this.handlersProvider = handlersProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(OutboxEvent event) {
        String correlationId = extractCorrelationId(event.getPayload());
        executor.submit(() -> {
            TenantContext.set(TenantContext.PrincipalContext.system(event.getTenantId(), "local-worker@forge.dev"));
            MDC.put(CorrelationIdFilter.MDC_CORRELATION_ID, correlationId);
            MDC.put(CorrelationIdFilter.MDC_TENANT_ID, event.getTenantId());
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
                MDC.remove(CorrelationIdFilter.MDC_CORRELATION_ID);
                MDC.remove(CorrelationIdFilter.MDC_TENANT_ID);
            }
        });
    }

    private String extractCorrelationId(String payload) {
        try {
            String fromPayload = objectMapper.readTree(payload).path("correlationId").asText("");
            if (!fromPayload.isBlank()) {
                return fromPayload;
            }
        } catch (Exception ignored) {
            // fall back to current/new correlation ID
        }
        return CorrelationIdFilter.currentOrNew();
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
