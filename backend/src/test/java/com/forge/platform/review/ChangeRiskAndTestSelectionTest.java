package com.forge.platform.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.platform.ci.CiBuildRepository;
import com.forge.platform.codeintel.ArchitectureDriftAnalyzer;
import com.forge.platform.codeintel.CodeDependencyEntity;
import com.forge.platform.codeintel.CodeIntelDtos;
import com.forge.platform.codeintel.CodeIntelligenceService;
import com.forge.platform.codeintel.JavaAstAnalyzer;
import com.forge.platform.repository.SourceFileEntity;
import com.forge.platform.repository.SourceFileJpaRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChangeRiskAndTestSelectionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChangeRiskEngine riskEngine = new ChangeRiskEngine(objectMapper);

    @Test
    void blocksOnCriticalSecurityOrBreakingApiAndPassesCleanLowRiskPrWithDisclaimer() {
        ChangeRiskPolicyEntity policy = ChangeRiskPolicyEntity.defaultPolicy("t-1", null, "owner@acme.io");

        // 1. Clean PR -> PASS with explicit disclaimer
        var cleanFiles = List.of(new UnifiedDiffParser.ParsedDiffFile(
            "src/main/java/com/acme/util/DateHelper.java", "", "public class DateHelper {}", List.of(), List.of()
        ));
        var passAssessment = riskEngine.evaluate(cleanFiles, List.of(), List.of(), null, policy);
        assertThat(passAssessment.mergeDecision()).isEqualTo("PASS");
        assertThat(passAssessment.riskScore()).isLessThanOrEqualTo(25);
        assertThat(passAssessment.safetyDisclaimer()).contains("NOT a guarantee of production safety");

        // 2. Breaking API -> BLOCK with exact policy trigger and explainable factor
        var apiFinding = new AiReviewEngine.FindingProposal(
            "openapi.yaml", 12, "API_COMPATIBILITY", "CRITICAL", 0.95,
            "API-BREAKING-001", "Endpoint removed", "Breaks consumers", "- /v1/pay:", ""
        );
        var blockAssessment = riskEngine.evaluate(cleanFiles, List.of(apiFinding), List.of(), null, policy);
        assertThat(blockAssessment.mergeDecision()).isEqualTo("BLOCK");
        assertThat(blockAssessment.triggeredPolicies()).contains("POLICY_BLOCK_BREAKING_API");
        assertThat(blockAssessment.factors()).isNotEmpty();
        assertThat(blockAssessment.breakingApiCount()).isEqualTo(1);
    }

    @Test
    void testSelectionNeverClaimsUnexecutedTestsPassed() {
        SourceFileJpaRepository fileRepo = mock(SourceFileJpaRepository.class);
        CiBuildRepository ciRepo = mock(CiBuildRepository.class);

        SourceFileEntity unitTest = SourceFileEntity.create(
            "t-1", "r-1", "src/test/java/com/acme/SettlementServiceTest.java", "java", 1, "hash", "class SettlementServiceTest {}"
        );
        when(fileRepo.findByRepositoryIdAndTenantIdOrderByFilePathAsc("r-1", "t-1")).thenReturn(List.of(unitTest));
        when(ciRepo.findByTenantIdOrderByTriggeredAtDesc("t-1")).thenReturn(List.of());

        TestSelectionService svc = new TestSelectionService(fileRepo, ciRepo);
        var changed = List.of(new UnifiedDiffParser.ParsedDiffFile(
            "src/main/java/com/acme/SettlementService.java", "", "class SettlementService {}", List.of(), List.of()
        ));
        var impact = new CodeIntelDtos.ChangeImpactResponse(
            "r-1", "SettlementService", 2, 1, "MEDIUM",
            List.of(new CodeIntelDtos.ImpactedNode("TransferControllerTest", "src/test/java/com/acme/TransferControllerTest.java", 2, "TESTS", true, "2-hop caller")),
            List.of()
        );

        var plan = svc.selectTests("t-1", "r-1", changed, List.of(impact));
        assertThat(plan.executionStatus()).isEqualTo("NOT_EXECUTED");
        assertThat(plan.disclaimer()).contains("NOT executed");
        assertThat(plan.selectedTests()).extracting(TestSelectionService.SelectedTest::testName)
            .contains("SettlementServiceTest", "TransferControllerTest");
        assertThat(plan.selectedTests()).allMatch(t -> "NOT_EXECUTED".equals(t.executionStatus()));
    }

    @Test
    void detectsCircularDependenciesAndArchitectureLayerDrift() {
        CodeIntelligenceService intel = new CodeIntelligenceService(
            null, null, null, null, null, new JavaAstAnalyzer(), null, null, null, objectMapper,
            mock(org.springframework.transaction.PlatformTransactionManager.class)
        );
        ArchitectureDriftAnalyzer driftAnalyzer = new ArchitectureDriftAnalyzer(null, null, intel);

        List<CodeDependencyEntity> deps = List.of(
            // Layer violation: API Controller directly injects DATA Repository
            CodeDependencyEntity.create("t-1", "r-1", "OrderController", "src/api/OrderController.java",
                "OrderRepository", "src/data/OrderRepository.java", "INJECTS"),
            // Circular dependency: ServiceA -> ServiceB -> ServiceA
            CodeDependencyEntity.create("t-1", "r-1", "ServiceA", "src/service/ServiceA.java",
                "ServiceB", "src/service/ServiceB.java", "INJECTS"),
            CodeDependencyEntity.create("t-1", "r-1", "ServiceB", "src/service/ServiceB.java",
                "ServiceA", "src/service/ServiceA.java", "INJECTS")
        );

        var drift = driftAnalyzer.evaluateEdges("r-1", "acme/orders", deps, null);
        assertThat(drift.violationCount()).isEqualTo(1);
        assertThat(drift.violations().get(0).ruleId()).isEqualTo("ARCH-001");
        assertThat(drift.cycleCount()).isEqualTo(1);
        assertThat(drift.circularDependencies().get(0)).contains("ServiceA", "ServiceB");
    }
}
