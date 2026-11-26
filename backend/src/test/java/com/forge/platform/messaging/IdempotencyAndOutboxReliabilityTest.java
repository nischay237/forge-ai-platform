package com.forge.platform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.audit.AuditService;
import com.forge.platform.common.CorrelationIdFilter;
import com.forge.platform.sse.PipelineEvent;
import com.forge.platform.sse.SseHubService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

        assertThat(idempotencyService.tryClaimEvent(key, tenantId, "TEST_EVENT")).isTrue();
        assertThat(idempotencyService.tryClaimEvent(key, tenantId, "TEST_EVENT")).isFalse();

        idempotencyService.releaseClaim(key);
        assertThat(idempotencyService.tryClaimEvent(key, tenantId, "TEST_EVENT")).isTrue();

        idempotencyService.markCompleted(key);
        assertThat(idempotencyService.tryClaimEvent(key, tenantId, "TEST_EVENT")).isFalse();
        idempotencyService.releaseClaim(key);
        assertThat(idempotencyService.tryClaimEvent(key, tenantId, "TEST_EVENT")).isFalse();
    }

    @Test
    void outboxStatsAreStrictlyTenantScopedAndEnqueuePropagatesCorrelationId() {
        String tenantA = UUID.randomUUID().toString();
        String tenantB = UUID.randomUUID().toString();

        MDC.put(CorrelationIdFilter.MDC_CORRELATION_ID, "corr-outbox-99");
        OutboxEvent saved;
        try {
            saved = outboxService.enqueue(tenantA, "PR_REVIEW", "rev-1", "PR_REVIEW_REQUESTED",
                ForgeTopics.PR_REVIEW_REQUESTED, Map.of("tenantId", tenantA, "reviewId", "rev-1"));
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_CORRELATION_ID);
        }

        assertThat(saved.getPayload()).contains("corr-outbox-99");

        Map<String, Object> statsA = outboxService.statsForTenant(tenantA);
        Map<String, Object> statsB = outboxService.statsForTenant(tenantB);

        assertThat(((Number) statsA.get("pending")).longValue()).isGreaterThanOrEqualTo(1L);
        assertThat(((Number) statsB.get("pending")).longValue()).isZero();
        assertThat(((Number) statsB.get("published")).longValue()).isZero();
    }

    @Test
    void concurrentOutboxClaimGuaranteesZeroDuplicateClaimsAcrossReplicas() throws Exception {
        String tenantId = UUID.randomUUID().toString();
        Set<String> enqueuedIds = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            OutboxEvent ev = outboxService.enqueue(tenantId, "PR_REVIEW", "rev-conc-" + i,
                "PR_REVIEW_REQUESTED", ForgeTopics.PR_REVIEW_REQUESTED,
                Map.of("tenantId", tenantId, "reviewId", "rev-conc-" + i));
            enqueuedIds.add(ev.getId());
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<OutboxEvent>> f1 = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return outboxService.claimBatch("replica-A");
            });
            Future<List<OutboxEvent>> f2 = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return outboxService.claimBatch("replica-B");
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Set<String> idsA = new HashSet<>();
            f1.get(10, TimeUnit.SECONDS).forEach(e -> idsA.add(e.getId()));
            Set<String> idsB = new HashSet<>();
            f2.get(10, TimeUnit.SECONDS).forEach(e -> idsB.add(e.getId()));

            // Strictly zero overlap between replicas!
            Set<String> overlap = new HashSet<>(idsA);
            overlap.retainAll(idsB);
            assertThat(overlap).isEmpty();

            // Every one of our newly enqueued events was claimed at most once (and at least once across the two replicas or scheduler)
            Set<String> combined = new HashSet<>(idsA);
            combined.addAll(idsB);
            assertThat(combined).doesNotHaveDuplicates();
            assertThat(enqueuedIds).allSatisfy(id ->
                assertThat(outboxEventRepository.findById(id).orElseThrow().getStatus()).isNotEqualTo("PENDING")
            );
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deadLetterRequeueResetsOnlyTargetTenantEvents() {
        String tenantA = UUID.randomUUID().toString();
        String tenantB = UUID.randomUUID().toString();

        OutboxEvent evA = OutboxEvent.pending(tenantA, "PR_REVIEW", "r-a", "PR_REVIEW_REQUESTED",
            ForgeTopics.PR_REVIEW_REQUESTED, "{}");
        evA.recordFailure("broker timeout", 1);
        outboxEventRepository.save(evA);

        OutboxEvent evB = OutboxEvent.pending(tenantB, "PR_REVIEW", "r-b", "PR_REVIEW_REQUESTED",
            ForgeTopics.PR_REVIEW_REQUESTED, "{}");
        evB.recordFailure("broker timeout", 1);
        outboxEventRepository.save(evB);

        int requeued = outboxService.requeueDeadLetterEvents(tenantA);
        assertThat(requeued).isEqualTo(1);
        assertThat(outboxEventRepository.findById(evA.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
        assertThat(outboxEventRepository.findById(evB.getId()).orElseThrow().getStatus()).isEqualTo("DEAD_LETTER");
    }

    @Test
    void redisCacheAndRateLimiterDegradeGracefullyWhileConsumeOnceFailsClosed() {
        StringRedisTemplate brokenRedis = mock(StringRedisTemplate.class);
        when(brokenRedis.opsForValue()).thenThrow(new RedisConnectionFailureException("Redis unreachable"));
        when(brokenRedis.delete(any(String.class))).thenThrow(new RedisConnectionFailureException("Redis unreachable"));
        when(brokenRedis.execute(any(), any(), any())).thenThrow(new RedisConnectionFailureException("Redis unreachable"));

        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        RedisForgeCacheService cache = new RedisForgeCacheService(brokenRedis, metrics);
        RedisRateLimiterService limiter = new RedisRateLimiterService(brokenRedis, metrics);

        // Cache get/put/evict must never throw during Redis outage
        cache.put("pred-overview:t1", "{}", Duration.ofSeconds(30));
        assertThat(cache.get("pred-overview:t1")).isEmpty();
        cache.evict("pred-overview:t1");
        // Single-use ticket redemption must fail closed (Optional.empty)
        assertThat(cache.consumeOnce("sse:ticket:abc")).isEmpty();
        // Rate limiter fails open with error metric
        assertThat(limiter.allowRequest("login:10.0.0.1", 10, Duration.ofMinutes(1))).isTrue();

        assertThat(metrics.counter("forge.redis.errors", "operation", "get").count()).isEqualTo(1.0);
        assertThat(metrics.counter("forge.redis.errors", "operation", "consume_once").count()).isEqualTo(1.0);
        assertThat(metrics.counter("forge.redis.errors", "operation", "rate_limit").count()).isEqualTo(1.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void kafkaListenerRoutesRetryTopicMessagesPropagatesCorrelationIdAndTracksDltMetrics() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        AtomicReference<String> observedCorrId = new AtomicReference<>();
        DomainEventHandler fakeHandler = new DomainEventHandler() {
            @Override
            public boolean supports(String topic) {
                return ForgeTopics.PR_REVIEW_REQUESTED.equals(topic);
            }

            @Override
            public void handle(String eventId, String tenantId, String payload) {
                observedCorrId.set(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID));
                handled.incrementAndGet();
            }
        };
        ObjectProvider<DomainEventHandler> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> Stream.of(fakeHandler));

        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        SseHubService sseMock = mock(SseHubService.class);
        KafkaDomainEventListener listener = new KafkaDomainEventListener(
            provider, objectMapper, mock(AuditService.class), sseMock, metrics
        );

        String payload = "{\"tenantId\":\"t-1\",\"reviewId\":\"r-1\"}";
        listener.onMessage(
            payload,
            ForgeTopics.PR_REVIEW_REQUESTED + "-retry-0",
            ForgeTopics.PR_REVIEW_REQUESTED,
            "t-1:r-1",
            "evt-123".getBytes(StandardCharsets.UTF_8),
            "t-1".getBytes(StandardCharsets.UTF_8),
            "corr-kafka-777".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(handled.get()).isEqualTo(1);
        assertThat(observedCorrId.get()).isEqualTo("corr-kafka-777");
        assertThat(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID)).isNull();

        // Verify DLT routing for PR_PREDICTION_REQUESTED emits PR_PREDICTION and increments DLT metric
        listener.onDeadLetter(
            "{\"tenantId\":\"t-1\",\"reviewId\":\"rev-pred-1\"}",
            ForgeTopics.PR_PREDICTION_REQUESTED + ".dlt",
            ForgeTopics.PR_PREDICTION_REQUESTED
        );
        assertThat(metrics.counter("forge.kafka.dlt.events").count()).isEqualTo(1.0);
        ArgumentCaptor<PipelineEvent> captor = ArgumentCaptor.forClass(PipelineEvent.class);
        verify(sseMock).publish(eq("t-1"), captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("PR_PREDICTION");
    }
}
