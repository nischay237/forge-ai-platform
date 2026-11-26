package com.forge.platform.sse;

import java.util.List;
import java.util.Optional;

/**
 * Allows domain services to reconstruct a tenant-scoped {@link OperationStatusSnapshot}
 * from persisted database state when an in-memory SSE operation ring-buffer entry is
 * absent (for example, after a server restart or cross-replica browser refresh).
 */
public interface OperationStatusFallbackProvider {

    /**
     * Pipeline event types supported by this provider (e.g. {@code PR_REVIEW}, {@code PR_PREDICTION}, {@code REPO_INDEX}).
     */
    List<String> supportedEventTypes();

    /**
     * Reconstructs a strictly tenant-scoped status snapshot from persisted domain entities.
     */
    Optional<OperationStatusSnapshot> findFallbackSnapshot(String tenantId, String eventType, String resourceId);
}
