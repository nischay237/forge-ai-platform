package com.forge.platform.sse;

import java.time.Instant;
import java.util.List;

/**
 * Tenant-scoped live/recent operation status snapshot used by the frontend to recover
 * real pipeline stage progress cleanly across browser refreshes and SSE reconnections.
 */
public record OperationStatusSnapshot(
    String operationId,
    String eventType,
    String resourceId,
    String correlationId,
    String status,
    String currentStage,
    String currentStageLabel,
    int completedStages,
    int totalStages,
    long latestSequence,
    Instant startedAt,
    Instant updatedAt,
    Instant completedAt,
    List<PipelineEvent> events
) {}
