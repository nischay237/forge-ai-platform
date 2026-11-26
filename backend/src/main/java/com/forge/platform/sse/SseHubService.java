package com.forge.platform.sse;

import com.forge.platform.common.RateLimitExceededException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SseHubService {

    private static final Logger log = LoggerFactory.getLogger(SseHubService.class);
    private static final int MAX_EMITTERS_PER_TENANT = 20;
    private static final int MAX_OPERATIONS_PER_TENANT = 40;
    private static final int MAX_EVENTS_PER_OPERATION = 32;
    private static final long EMITTER_TIMEOUT_MS = 30L * 60L * 1000L; // 30 minutes

    private final Map<String, List<SseEmitter>> emittersByTenant = new ConcurrentHashMap<>();
    private final Map<String, Map<String, TrackedOperation>> operationsByTenant = new ConcurrentHashMap<>();
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final Counter eventsDeliveredCounter;
    private final Counter eventsDroppedCounter;

    public SseHubService(MeterRegistry meterRegistry) {
        Gauge.builder("forge.sse.connections.active", activeConnections, AtomicInteger::get)
            .description("Active tenant-scoped SSE client connections")
            .register(meterRegistry);
        this.eventsDeliveredCounter = Counter.builder("forge.sse.events.delivered")
            .description("Pipeline SSE events delivered to subscribed clients")
            .register(meterRegistry);
        this.eventsDroppedCounter = Counter.builder("forge.sse.events.dropped")
            .description("Pipeline SSE event deliveries dropped due to stale or closed emitters")
            .register(meterRegistry);
    }

    public SseEmitter subscribe(String tenantId) {
        List<SseEmitter> list = emittersByTenant.computeIfAbsent(tenantId, k -> new CopyOnWriteArrayList<>());
        if (list.size() >= MAX_EMITTERS_PER_TENANT) {
            throw new RateLimitExceededException("Maximum concurrent live stream connections reached for this workspace.");
        }

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        list.add(emitter);
        activeConnections.incrementAndGet();

        AtomicInteger cleanedUp = new AtomicInteger(0);
        Runnable cleanup = () -> {
            if (cleanedUp.compareAndSet(0, 1)) {
                if (list.remove(emitter)) {
                    activeConnections.decrementAndGet();
                }
                if (list.isEmpty()) {
                    emittersByTenant.remove(tenantId, list);
                }
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            PipelineEvent connected = PipelineEvent.of(
                "CONNECTED", tenantId, "CONNECTED", 100, "Subscribed to FORGE live pipeline stream"
            );
            emitter.send(SseEmitter.event()
                .id(connected.eventId())
                .name("connected")
                .data(connected));
        } catch (Exception ex) {
            cleanup.run();
        }
        return emitter;
    }

    public PipelineEvent publish(String tenantId, PipelineEvent event) {
        PipelineEvent sequenced = recordOperationEvent(tenantId, event);

        List<SseEmitter> list = emittersByTenant.get(tenantId);
        if (list == null || list.isEmpty()) {
            return sequenced;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                    .id(sequenced.eventId())
                    .name("pipeline")
                    .data(sequenced));
                eventsDeliveredCounter.increment();
            } catch (Exception ex) {
                log.debug("Removing stale SSE emitter for tenant {}", tenantId);
                eventsDroppedCounter.increment();
                if (list.remove(emitter)) {
                    activeConnections.decrementAndGet();
                }
            }
        }
        return sequenced;
    }

    public Optional<OperationStatusSnapshot> getOperationSnapshot(String tenantId, String operationId) {
        Map<String, TrackedOperation> tenantOps = operationsByTenant.get(tenantId);
        if (tenantOps == null) {
            return Optional.empty();
        }
        TrackedOperation op = tenantOps.get(operationId);
        return op != null ? Optional.of(op.toSnapshot()) : Optional.empty();
    }

    public List<OperationStatusSnapshot> listRecentOperations(String tenantId, String resourceId) {
        Map<String, TrackedOperation> tenantOps = operationsByTenant.get(tenantId);
        if (tenantOps == null || tenantOps.isEmpty()) {
            return List.of();
        }
        return tenantOps.values().stream()
            .filter(op -> resourceId == null || resourceId.isBlank() || resourceId.equals(op.resourceId))
            .map(TrackedOperation::toSnapshot)
            .sorted(Comparator.comparing(OperationStatusSnapshot::updatedAt).reversed())
            .limit(20)
            .toList();
    }

    private PipelineEvent recordOperationEvent(String tenantId, PipelineEvent event) {
        if ("CONNECTED".equalsIgnoreCase(event.eventType())) {
            return event;
        }
        Map<String, TrackedOperation> tenantOps = operationsByTenant
            .computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>());

        String opId = event.operationId();
        TrackedOperation tracked = tenantOps.compute(opId, (k, existing) -> {
            if (existing == null) {
                return new TrackedOperation(opId, event.eventType(), event.resourceId(), event.correlationId(), event.timestamp());
            }
            // If a previously completed/failed operation is explicitly restarted (new STARTED event), start fresh timeline
            if (("COMPLETED".equals(existing.status) || "FAILED".equals(existing.status))
                && "STARTED".equalsIgnoreCase(event.status())) {
                return new TrackedOperation(opId, event.eventType(), event.resourceId(), event.correlationId(), event.timestamp());
            }
            return existing;
        });

        PipelineEvent sequenced = tracked.append(event);
        trimTenantOperations(tenantOps);
        return sequenced;
    }

    private void trimTenantOperations(Map<String, TrackedOperation> tenantOps) {
        if (tenantOps.size() <= MAX_OPERATIONS_PER_TENANT) {
            return;
        }
        tenantOps.values().stream()
            .sorted(Comparator.comparing(op -> op.updatedAt))
            .limit(tenantOps.size() - MAX_OPERATIONS_PER_TENANT)
            .forEach(oldest -> tenantOps.remove(oldest.operationId, oldest));
    }

    @Scheduled(fixedRate = 25000)
    public void sendHeartbeats() {
        emittersByTenant.forEach((tenantId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (Exception ex) {
                    if (emitters.remove(emitter)) {
                        activeConnections.decrementAndGet();
                    }
                }
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        emittersByTenant.values().forEach(emitters -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // best effort during graceful shutdown
                }
            }
            emitters.clear();
        });
        emittersByTenant.clear();
        activeConnections.set(0);
    }

    private static final class TrackedOperation {
        final String operationId;
        final String eventType;
        final String resourceId;
        final String correlationId;
        final Instant startedAt;
        final AtomicLong sequenceCounter = new AtomicLong(0);
        final Deque<PipelineEvent> events = new ConcurrentLinkedDeque<>();
        volatile String status = "RUNNING";
        volatile String currentStage = "STARTED";
        volatile String currentStageLabel = "Starting";
        volatile int totalStages = 0;
        volatile Instant updatedAt;
        volatile Instant completedAt;

        TrackedOperation(String operationId, String eventType, String resourceId, String correlationId, Instant startedAt) {
            this.operationId = operationId;
            this.eventType = eventType;
            this.resourceId = resourceId;
            this.correlationId = correlationId;
            this.startedAt = startedAt != null ? startedAt : Instant.now();
            this.updatedAt = this.startedAt;
        }

        synchronized PipelineEvent append(PipelineEvent raw) {
            long seq = raw.sequence() > 0 ? raw.sequence() : sequenceCounter.incrementAndGet();
            PipelineEvent enriched = raw.withSequence(seq);
            events.addLast(enriched);
            while (events.size() > MAX_EVENTS_PER_OPERATION) {
                events.pollFirst();
            }
            this.currentStage = enriched.stage();
            this.currentStageLabel = enriched.stageLabel();
            if (enriched.totalStages() > 0) {
                this.totalStages = enriched.totalStages();
            }
            this.updatedAt = enriched.timestamp() != null ? enriched.timestamp() : Instant.now();
            if ("COMPLETED".equalsIgnoreCase(enriched.status())) {
                this.status = "COMPLETED";
                this.completedAt = this.updatedAt;
            } else if ("FAILED".equalsIgnoreCase(enriched.status())) {
                this.status = "FAILED";
                this.completedAt = this.updatedAt;
            } else {
                this.status = "RUNNING";
            }
            return enriched;
        }

        synchronized OperationStatusSnapshot toSnapshot() {
            List<PipelineEvent> copy = new ArrayList<>(events);
            int completedCount = 0;
            if ("COMPLETED".equals(status)) {
                completedCount = totalStages > 0 ? totalStages : copy.size();
            } else {
                // Distinct stages strictly prior to the current active stage are completed
                completedCount = Math.max(0, copy.size() - 1);
            }
            return new OperationStatusSnapshot(
                operationId,
                eventType,
                resourceId,
                correlationId,
                status,
                currentStage,
                currentStageLabel,
                completedCount,
                totalStages,
                sequenceCounter.get(),
                startedAt,
                updatedAt,
                completedAt,
                copy
            );
        }
    }
}
