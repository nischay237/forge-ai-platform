package com.forge.platform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.audit.AuditService;
import com.forge.platform.sse.SseHubService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles({"local", "test"})
class IdempotencyAndOutboxReliabilityTest {

    @Autowired private IdempotencyService idempotencyService;
    @Autowired private OutboxService outboxService;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void idempotencySupportsLeaseReleaseOnFailureAndPermanentDeduplicationOnCompletion() {
        String key = "test-idem-" + UUID.randomUUID();
        String tenantId = UUID.randomUUID().toString();

        // First claim succeeds
        assertThat(idempotencyService.tryClaimEvent(key, tenantId, "TEST_EVENT")).isTrue();
        // Concurrent second claim fails cleanly without exception
        assertThat(idempotencyService.tryClaimEvent(key, tenantId, "TEST_EVENT")).isFalse();

        // Worker fails -> releases claim -> retry succeeds!
        idempotencyService.releaseClaim(key);
        assertThat(idempotencyService.tryClaimEvent(key, tenantId, "TEST_EVENT")).isTrue();

        // Worker succeeds -> marks completed -> subsequent attempts stay deduplicated forever
        idempotencyService.markCompleted(key);
        assertThat(idempotencyService.tryClaimEvent(key, tenantId, "TEST_EVENT")).isFalse();
        idempotencyService.releaseClaim(key); // releaseClaim must NEVER delete COMPLETED events
        assertThat(idempotencyService.tryClaimEvent(key, tenantId, "TEST_EVENT")).isFalse();
    }

    @Test
    void outboxStatsAreStrictlyTenantScoped() {
        String tenantA = UUID.randomUUID().toString();
        String tenantB = UUID.randomUUID().toString();

        outboxService.enqueue(tenantA, "PR_REVIEW", "rev-1", "PR_REVIEW_REQUESTED",
            ForgeTopics.PR_REVIEW_REQUESTED, Map.of("tenantId", tenantA, "reviewId", "rev-1"));

        Map<String, Object> statsA = outboxService.statsForTenant(tenantA);
        Map<String, Object> statsB = outboxService.statsForTenant(tenantB);

        assertThat(((Number) statsA.get("pending")).longValue()).isGreaterThanOrEqualTo(1L);
        assertThat(((Number) statsB.get("pending")).longValue()).isZero();
        assertThat(((Number) statsB.get("published")).longValue()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void kafkaListenerRoutesRetryTopicMessagesUsingOriginalTopicHeader() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        DomainEventHandler fakeHandler = new DomainEventHandler() {
            @Override
            public boolean supports(String topic) {
                return ForgeTopics.PR_REVIEW_REQUESTED.equals(topic);
            }

            @Override
            public void handle(String eventId, String tenantId, String payload) {
                handled.incrementAndGet();
            }
        };
        ObjectProvider<DomainEventHandler> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> Stream.of(fakeHandler));

        KafkaDomainEventListener listener = new KafkaDomainEventListener(
            provider, objectMapper, mock(AuditService.class), mock(SseHubService.class)
        );

        String payload = "{\"tenantId\":\"t-1\",\"reviewId\":\"r-1\"}";
        listener.onMessage(
            payload,
            ForgeTopics.PR_REVIEW_REQUESTED + "-retry-0",
            ForgeTopics.PR_REVIEW_REQUESTED,
            "t-1:r-1",
            "evt-123".getBytes(StandardCharsets.UTF_8),
            "t-1".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(handled.get()).isEqualTo(1);
    }
}
