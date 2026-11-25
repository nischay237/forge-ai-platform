package com.forge.platform.sse;

import java.time.Instant;

public record PipelineEvent(
    String eventType,
    String resourceId,
    String stage,
    int progressPercent,
    String message,
    Instant timestamp
) {
    public static PipelineEvent of(String eventType, String resourceId, String stage, int progressPercent, String message) {
        return new PipelineEvent(eventType, resourceId, stage, progressPercent, message, Instant.now());
    }
}
