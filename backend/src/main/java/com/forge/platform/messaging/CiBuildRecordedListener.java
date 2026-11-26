package com.forge.platform.messaging;

import java.time.Instant;

/**
 * Decoupled hook allowing CI build ingestion (com.forge.platform.ci) to feed real historical
 * test/pipeline outcomes into Predictive Intelligence without introducing circular dependencies.
 */
public interface CiBuildRecordedListener {

    void onCiBuildRecorded(
        String tenantId,
        String repositoryId,
        String ciBuildId,
        String pipelineName,
        String commitSha,
        Integer prNumber,
        String status,
        int durationSeconds,
        String failureCategory,
        String rawLog,
        Instant triggeredAt
    );
}
