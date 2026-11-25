package com.forge.platform.sse;

import com.forge.platform.common.TenantContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/stream")
public class SseController {

    private final SseHubService sseHubService;

    public SseController(SseHubService sseHubService) {
        this.sseHubService = sseHubService;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents() {
        return sseHubService.subscribe(TenantContext.requireTenantId());
    }
}
