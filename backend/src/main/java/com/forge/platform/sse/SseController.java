package com.forge.platform.sse;

import com.forge.platform.common.ResourceNotFoundException;
import com.forge.platform.common.TenantContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

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

    /**
     * Authenticated, tenant-scoped recovery endpoint returning recent/active pipeline operations
     * so browser refreshes and reconnections restore exact backend stage progress without fake timers.
     */
    @GetMapping("/operations")
    public List<OperationStatusSnapshot> listOperations(
        @RequestParam(name = "resourceId", required = false) String resourceId
    ) {
        return sseHubService.listRecentOperations(TenantContext.requireTenantId(), resourceId);
    }

    /**
     * Authenticated, tenant-scoped lookup for a single operation's ordered stage timeline.
     */
    @GetMapping("/operations/{operationId}")
    public OperationStatusSnapshot getOperation(@PathVariable String operationId) {
        return sseHubService.getOperationSnapshot(TenantContext.requireTenantId(), operationId)
            .orElseThrow(() -> new ResourceNotFoundException("Pipeline operation not found: " + operationId));
    }
}
