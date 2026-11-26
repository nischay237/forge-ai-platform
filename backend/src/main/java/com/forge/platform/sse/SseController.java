package com.forge.platform.sse;

import com.forge.platform.common.TenantContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/stream")
public class SseController {

    private final SseHubService sseHubService;
    private final SseTicketService sseTicketService;

    public SseController(SseHubService sseHubService, SseTicketService sseTicketService) {
        this.sseHubService = sseHubService;
        this.sseTicketService = sseTicketService;
    }

    /**
     * Authenticated endpoint that issues a short-lived, single-use SSE ticket.
     */
    @PostMapping("/ticket")
    public SseTicketService.TicketResponse issueStreamTicket() {
        return sseTicketService.issueTicket(TenantContext.requireTenantId());
    }

    /**
     * Opens an SSE stream using either a single-use ticket (for browser EventSource)
     * or an already-authenticated Bearer JWT context (for non-browser SSE clients).
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@RequestParam(name = "ticket", required = false) String ticket) {
        TenantContext.PrincipalContext principal = TenantContext.get();
        String tenantId = (principal != null && principal.tenantId() != null && !principal.tenantId().isBlank())
            ? principal.tenantId()
            : sseTicketService.consumeTicket(ticket);
        return sseHubService.subscribe(tenantId);
    }
}
