package com.forge.platform.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/security")
public class SecurityController {

    public record TriageRequest(@NotBlank String status) {}

    private final SecurityService securityService;

    public SecurityController(SecurityService securityService) {
        this.securityService = securityService;
    }

    @GetMapping("/findings")
    public List<SecurityFindingEntity> listFindings(
        @RequestParam(required = false) String repositoryId,
        @RequestParam(required = false) String severity,
        @RequestParam(required = false) String status
    ) {
        return securityService.listFindings(repositoryId, severity, status);
    }

    @PatchMapping("/findings/{findingId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'REVIEWER')")
    public SecurityFindingEntity triage(
        @PathVariable String findingId,
        @Valid @RequestBody TriageRequest request
    ) {
        return securityService.triageFinding(findingId, request.status());
    }

    @PostMapping("/repositories/{repositoryId}/scan")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'REVIEWER')")
    public List<SecurityFindingEntity> scanRepository(@PathVariable String repositoryId) {
        return securityService.scanRepository(repositoryId);
    }

    @GetMapping("/repositories/{repositoryId}/sbom")
    public Map<String, Object> exportSbom(@PathVariable String repositoryId) {
        return securityService.exportRepositorySbom(repositoryId);
    }

    @GetMapping("/repositories/{repositoryId}/sarif")
    public Map<String, Object> exportSarif(@PathVariable String repositoryId) {
        return securityService.exportRepositorySarif(repositoryId);
    }

    @GetMapping("/suppressions")
    public List<FindingSuppressionEntity> listSuppressions() {
        return securityService.listSuppressions();
    }

    @PostMapping("/suppressions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public FindingSuppressionEntity createSuppression(@RequestBody SecurityService.CreateSuppressionRequest request) {
        return securityService.createSuppression(request);
    }

    @DeleteMapping("/suppressions/{suppressionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public void deleteSuppression(@PathVariable String suppressionId) {
        securityService.deleteSuppression(suppressionId);
    }
}
