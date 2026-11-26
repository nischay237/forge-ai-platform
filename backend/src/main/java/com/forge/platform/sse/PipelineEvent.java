package com.forge.platform.sse;

import com.forge.platform.common.CorrelationIdFilter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Structured real-time pipeline telemetry event emitted strictly from genuine backend stage transitions.
 * Includes stable eventId, operationId, correlationId, monotonic sequence number, stage progress
 * counts (stageIndex / totalStages), and optional structured details for expandable UI diagnostics.
 */
public record PipelineEvent(
    String eventId,
    String operationId,
    String correlationId,
    long sequence,
    String eventType,
    String resourceId,
    String stage,
    String stageLabel,
    String status,
    int stageIndex,
    int totalStages,
    int progressPercent,
    String message,
    Map<String, Object> details,
    Instant timestamp
) {
    public static PipelineEvent of(String eventType, String resourceId, String stage, int progressPercent, String message) {
        String resolvedStatus = inferStatus(stage);
        return new PipelineEvent(
            UUID.randomUUID().toString(),
            buildOperationId(eventType, resourceId),
            CorrelationIdFilter.currentOrNew(),
            0L,
            eventType,
            resourceId,
            stage,
            humanizeStage(stage),
            resolvedStatus,
            0,
            0,
            Math.max(0, Math.min(100, progressPercent)),
            message,
            Map.of(),
            Instant.now()
        );
    }

    public static PipelineEvent stage(
        String eventType,
        String resourceId,
        String stage,
        String stageLabel,
        String status,
        int stageIndex,
        int totalStages,
        String message,
        Map<String, Object> details
    ) {
        int pct;
        if ("COMPLETED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
            pct = 100;
        } else if (totalStages > 0 && stageIndex > 0) {
            pct = Math.max(0, Math.min(95, (int) Math.round(((stageIndex - 1.0) / totalStages) * 100.0)));
        } else {
            pct = 0;
        }
        return new PipelineEvent(
            UUID.randomUUID().toString(),
            buildOperationId(eventType, resourceId),
            CorrelationIdFilter.currentOrNew(),
            0L,
            eventType,
            resourceId,
            stage,
            stageLabel != null && !stageLabel.isBlank() ? stageLabel : humanizeStage(stage),
            status,
            stageIndex,
            totalStages,
            pct,
            message,
            details != null ? Map.copyOf(details) : Map.of(),
            Instant.now()
        );
    }

    public PipelineEvent withSequence(long nextSequence) {
        return new PipelineEvent(
            eventId,
            operationId,
            correlationId,
            nextSequence,
            eventType,
            resourceId,
            stage,
            stageLabel,
            status,
            stageIndex,
            totalStages,
            progressPercent,
            message,
            details,
            timestamp
        );
    }

    public static String buildOperationId(String eventType, String resourceId) {
        return eventType + ":" + resourceId;
    }

    private static String inferStatus(String stage) {
        if (stage == null) return "IN_PROGRESS";
        String upper = stage.toUpperCase();
        if (upper.equals("COMPLETED") || upper.endsWith("_COMPLETED")) return "COMPLETED";
        if (upper.equals("FAILED") || upper.endsWith("_FAILED")) return "FAILED";
        if (upper.equals("QUEUED") || upper.equals("STARTED")) return "STARTED";
        return "IN_PROGRESS";
    }

    private static String humanizeStage(String stage) {
        if (stage == null || stage.isBlank()) return "Processing";
        String[] parts = stage.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
