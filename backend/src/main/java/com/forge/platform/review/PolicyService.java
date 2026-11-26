package com.forge.platform.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.audit.AuditService;
import com.forge.platform.common.ForgeException;
import com.forge.platform.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class PolicyService {

    public record ArchitectureBoundaryRule(
        String id,
        String sourceLayer,
        String forbiddenTargetLayer,
        String severity,
        String description
    ) {}

    public record PolicyDto(
        String id,
        String repositoryId,
        int version,
        boolean blockOnCriticalSecurity,
        boolean blockOnBreakingApi,
        boolean blockOnDestructiveMigration,
        boolean blockOnPromptInjection,
        int maxRiskScoreForPass,
        int maxRiskScoreForWarn,
        boolean minTestsForHighImpact,
        List<String> sensitivePaths,
        List<ArchitectureBoundaryRule> architectureRules,
        String updatedBy,
        Instant updatedAt
    ) {}

    public record UpdatePolicyRequest(
        String repositoryId,
        Boolean blockOnCriticalSecurity,
        Boolean blockOnBreakingApi,
        Boolean blockOnDestructiveMigration,
        Boolean blockOnPromptInjection,
        Integer maxRiskScoreForPass,
        Integer maxRiskScoreForWarn,
        Boolean minTestsForHighImpact,
        List<String> sensitivePaths,
        List<ArchitectureBoundaryRule> architectureRules
    ) {}

    private final ChangeRiskPolicyRepository policyRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public PolicyService(
        ChangeRiskPolicyRepository policyRepository,
        AuditService auditService,
        ObjectMapper objectMapper
    ) {
        this.policyRepository = policyRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ChangeRiskPolicyEntity resolveEffectivePolicy(String tenantId, String repositoryId) {
        if (repositoryId != null && !repositoryId.isBlank()) {
            var repoPolicy = policyRepository.findFirstByTenantIdAndRepositoryIdOrderByVersionDesc(tenantId, repositoryId);
            if (repoPolicy.isPresent()) {
                return repoPolicy.get();
            }
        }
        return policyRepository.findFirstByTenantIdAndRepositoryIdIsNullOrderByVersionDesc(tenantId)
            .orElseGet(() -> ChangeRiskPolicyEntity.defaultPolicy(tenantId, null, "system-default"));
    }

    @Transactional(readOnly = true)
    public PolicyDto getPolicy(String repositoryId) {
        String tenantId = TenantContext.requireTenantId();
        return toDto(resolveEffectivePolicy(tenantId, repositoryId));
    }

    @Transactional
    public PolicyDto updatePolicy(UpdatePolicyRequest request) {
        String tenantId = TenantContext.requireTenantId();
        String actorEmail = TenantContext.requireEmail();
        String repoId = (request.repositoryId() != null && !request.repositoryId().isBlank())
            ? request.repositoryId().trim()
            : null;

        ChangeRiskPolicyEntity current = resolveEffectivePolicy(tenantId, repoId);
        int passMax = request.maxRiskScoreForPass() != null ? request.maxRiskScoreForPass() : current.getMaxRiskScoreForPass();
        int warnMax = request.maxRiskScoreForWarn() != null ? request.maxRiskScoreForWarn() : current.getMaxRiskScoreForWarn();
        if (passMax < 0 || warnMax > 100 || passMax >= warnMax) {
            throw new ForgeException(HttpStatus.BAD_REQUEST, "INVALID_POLICY_THRESHOLDS",
                "Thresholds must satisfy 0 <= maxRiskScoreForPass < maxRiskScoreForWarn <= 100");
        }

        ChangeRiskPolicyEntity next = ChangeRiskPolicyEntity.defaultPolicy(tenantId, repoId, actorEmail);
        next.setVersion(current.getVersion() + 1);
        next.setBlockOnCriticalSecurity(request.blockOnCriticalSecurity() != null ? request.blockOnCriticalSecurity() : current.isBlockOnCriticalSecurity());
        next.setBlockOnBreakingApi(request.blockOnBreakingApi() != null ? request.blockOnBreakingApi() : current.isBlockOnBreakingApi());
        next.setBlockOnDestructiveMigration(request.blockOnDestructiveMigration() != null ? request.blockOnDestructiveMigration() : current.isBlockOnDestructiveMigration());
        next.setBlockOnPromptInjection(request.blockOnPromptInjection() != null ? request.blockOnPromptInjection() : current.isBlockOnPromptInjection());
        next.setMaxRiskScoreForPass(passMax);
        next.setMaxRiskScoreForWarn(warnMax);
        next.setMinTestsForHighImpact(request.minTestsForHighImpact() != null ? request.minTestsForHighImpact() : current.isMinTestsForHighImpact());
        try {
            next.setSensitivePathsJson(request.sensitivePaths() != null
                ? objectMapper.writeValueAsString(request.sensitivePaths())
                : current.getSensitivePathsJson());
            next.setArchitectureRulesJson(request.architectureRules() != null
                ? objectMapper.writeValueAsString(request.architectureRules())
                : current.getArchitectureRulesJson());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize policy rules", ex);
        }
        next.setUpdatedAt(Instant.now());
        policyRepository.save(next);

        auditService.recordCurrent("RISK_POLICY_UPDATED", "CHANGE_RISK_POLICY", next.getId(),
            "Published Change Risk Policy v" + next.getVersion()
                + " (pass<=" + passMax + ", warn<=" + warnMax
                + ", blockCritSec=" + next.isBlockOnCriticalSecurity()
                + ", blockBreakingApi=" + next.isBlockOnBreakingApi()
                + ", blockDestructiveMigration=" + next.isBlockOnDestructiveMigration() + ")");

        return toDto(next);
    }

    public List<ArchitectureBoundaryRule> parseArchitectureRules(ChangeRiskPolicyEntity policy) {
        if (policy.getArchitectureRulesJson() == null || policy.getArchitectureRulesJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(policy.getArchitectureRulesJson(), new TypeReference<>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private PolicyDto toDto(ChangeRiskPolicyEntity p) {
        List<String> sensitive = List.of();
        if (p.getSensitivePathsJson() != null && !p.getSensitivePathsJson().isBlank()) {
            try {
                sensitive = objectMapper.readValue(p.getSensitivePathsJson(), new TypeReference<>() {});
            } catch (Exception ignored) {
                sensitive = List.of();
            }
        }
        return new PolicyDto(
            p.getId(),
            p.getRepositoryId(),
            p.getVersion(),
            p.isBlockOnCriticalSecurity(),
            p.isBlockOnBreakingApi(),
            p.isBlockOnDestructiveMigration(),
            p.isBlockOnPromptInjection(),
            p.getMaxRiskScoreForPass(),
            p.getMaxRiskScoreForWarn(),
            p.isMinTestsForHighImpact(),
            sensitive,
            parseArchitectureRules(p),
            p.getUpdatedBy(),
            p.getUpdatedAt()
        );
    }
}
