package com.forge.platform.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class SseHubService {

    private static final Logger log = LoggerFactory.getLogger(SseHubService.class);
    private final Map<String, List<SseEmitter>> emittersByTenant = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String tenantId) {
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> list = emittersByTenant.computeIfAbsent(tenantId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        Runnable cleanup = () -> list.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            emitter.send(SseEmitter.event()
                .name("connected")
                .data(PipelineEvent.of("CONNECTED", tenantId, "CONNECTED", 100, "Subscribed to FORGE live pipeline stream")));
        } catch (Exception ex) {
            list.remove(emitter);
        }
        return emitter;
    }

    public void publish(String tenantId, PipelineEvent event) {
        List<SseEmitter> list = emittersByTenant.get(tenantId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("pipeline").data(event));
            } catch (Exception ex) {
                log.debug("Removing stale SSE emitter for tenant {}", tenantId);
                list.remove(emitter);
            }
        }
    }

    @Scheduled(fixedRate = 25000)
    public void sendHeartbeats() {
        emittersByTenant.forEach((tenantId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (Exception ex) {
                    emitters.remove(emitter);
                }
            }
        });
    }
}
