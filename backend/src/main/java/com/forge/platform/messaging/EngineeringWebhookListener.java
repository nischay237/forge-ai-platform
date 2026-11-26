package com.forge.platform.messaging;

import java.util.Map;

/**
 * Decoupled hook allowing verified webhook events (e.g. GitHub workflow_run, deployment_status)
 * to feed historical engineering telemetry without creating circular module dependencies.
 */
public interface EngineeringWebhookListener {

    boolean supportsGithubEvent(String eventName);

    Map<String, Object> onGithubEvent(
        String tenantId,
        String eventName,
        String deliveryKey,
        String rawPayloadJson
    );
}
