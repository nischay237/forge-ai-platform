package com.forge.platform.prediction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class PredictionDtos {

    public static final String ENGINE_VERSION = "forge-predictive-baseline-v1.0";
    public static final String MODEL_TYPE_DETERMINISTIC = "DETERMINISTIC_BASELINE_V1";
    public static final String INSUFFICIENT_DATA_MESSAGE =
        "Insufficient historical data for a reliable prediction.";

    private PredictionDtos() {}

    // --- Historical Ingestion Requests ---

    public record IngestTestExecutionRequest(
        @NotBlank @Size(max = 36) String repositoryId,
        @Size(max = 36) String ciBuildId,
        Integer prNumber,
        @Size(max = 64) String commitSha,
        @NotBlank @Size(max = 240) String testName,
        @Size(max = 500) String testFilePath,
        @Size(max = 160) String moduleName,
        @NotBlank @Size(max = 24) String outcome, // PASSED | FAILED | SKIPPED | FLAKY
        Long durationMs,
        @Size(max = 4000) String errorSummary,
        @Size(max = 180) String externalEventKey,
        Instant executedAt
    ) {}

    public record IngestDeploymentRequest(
        @NotBlank @Size(max = 36) String repositoryId,
        @NotBlank @Size(max = 140) String serviceName,
        @Size(max = 48) String environment,
        Integer prNumber,
        @Size(max = 64) String commitSha,
        @NotBlank @Size(max = 24) String outcome, // SUCCEEDED | FAILED | ROLLED_BACK
        @Size(max = 36) String rollbackOfDeploymentId,
        Boolean changedConfig,
        @Size(max = 4000) String summary,
        @Size(max = 180) String externalEventKey,
        Instant deployedAt
    ) {}

    public record IngestIncidentRequest(
        @NotBlank @Size(max = 36) String repositoryId,
        @NotBlank @Size(max = 260) String title,
        @NotBlank @Size(max = 16) String severity, // SEV1 | SEV2 | SEV3 | SEV4
        @NotBlank @Size(max = 140) String serviceName,
        @Size(max = 100) List<String> affectedModules,
        @Size(max = 36) String relatedDeploymentId,
        Integer relatedPrNumber,
        @Size(max = 24) String status, // OPEN | MITIGATED | RESOLVED
        @Size(max = 4000) String evidenceNote,
        @Size(max = 180) String externalEventKey,
        Instant occurredAt,
        Instant resolvedAt
    ) {}

    public record IngestDefectRequest(
        @NotBlank @Size(max = 36) String repositoryId,
        @NotBlank @Size(max = 260) String title,
        @NotBlank @Size(max = 16) String severity, // CRITICAL | HIGH | MEDIUM | LOW
        @Size(max = 160) String moduleName,
        @Size(max = 100) List<String> filePaths,
        Integer introducedPrNumber,
        Integer resolvedPrNumber,
        @Size(max = 24) String status, // OPEN | FIXED
        @Size(max = 180) String externalEventKey,
        Instant reportedAt
    ) {}

    public record PredictionRunHistoryItem(
        String predictionRunId,
        String commitSha,
        String engineVersion,
        String modelType,
        int overallEngineeringRiskScore,
        String overallRiskLevel,
        int historicalSampleSize,
        Instant createdAt
    ) {}

    public record IngestionResultDto(
        String id,
        String recordType,
        boolean duplicate,
        String externalEventKey,
        Instant recordedAt
    ) {}

    // --- Feature & Signal Breakdown DTOs ---

    public record SignalChannelScore(
        String channel, // STATIC_CODE_ANALYSIS | DEPENDENCY_IMPACT | SECURITY | PREDICTED_DEFECT | PREDICTED_TEST_FAILURE | PREDICTED_OPERATIONAL | PERFORMANCE_REGRESSION_WARNING
        String label,
        int rawScore,
        int deduplicatedContribution,
        String riskLevel,
        String signalKind, // DETERMINISTIC_STATIC | PREDICTIVE_BASELINE | STATIC_PERF_WARNING
        String summary
    ) {}

    public record TargetRiskBreakdown(
        String scope, // FILE | CLASS | METHOD | MODULE | SERVICE
        String name,
        String filePath,
        String riskLevel,
        int riskScore,
        String reason
    ) {}

    public record EvidenceDto(
        String id,
        String signalSource,
        String evidenceType,
        String targetRef,
        String summary,
        int weight,
        int historicalCount,
        Instant observedAt
    ) {}

    public record PredictionItemDto(
        String id,
        String category, // BUG_RISK | TEST_FAILURE_RISK | PRODUCTION_INCIDENT_RISK | CHANGE_FAILURE_RISK | PERFORMANCE_REGRESSION
        String riskLevel, // LOW | MEDIUM | HIGH | CRITICAL | INSUFFICIENT_DATA
        int riskScore,
        BigDecimal probability, // null unless statistically justified & calibrated
        String confidenceLevel, // HIGH | MEDIUM | LOW | INSUFFICIENT_DATA
        boolean insufficientData,
        int historicalSampleSize,
        Instant dataFreshnessAt,
        String ruleOrModelVersion,
        String explanation,
        String limitations,
        String recommendedAction,
        List<TargetRiskBreakdown> targetBreakdowns,
        List<EvidenceDto> evidence
    ) {}

    public record PrPredictionResponse(
        String predictionRunId,
        String reviewId,
        String repositoryId,
        String repositoryName,
        int prNumber,
        String prTitle,
        String commitSha,
        String engineVersion,
        String modelType,
        int overallEngineeringRiskScore,
        String overallRiskLevel,
        String policyBoundaryNote,
        int historicalSampleSize,
        Instant dataFreshnessAt,
        String dataAvailabilitySummary,
        List<SignalChannelScore> signalChannels,
        List<PredictionItemDto> predictions,
        Map<String, Object> featureSummary,
        Instant createdAt
    ) {}

    public record PredictionEvidenceResponse(
        String predictionRunId,
        String reviewId,
        String engineVersion,
        List<TargetRiskBreakdown> byFile,
        List<TargetRiskBreakdown> byClass,
        List<TargetRiskBreakdown> byMethod,
        List<TargetRiskBreakdown> byModule,
        List<TargetRiskBreakdown> byService,
        List<EvidenceDto> evidence
    ) {}

    public record PredictedTestRiskItem(
        String testName,
        String testFilePath,
        String riskLevel,
        int riskScore,
        String confidenceLevel,
        String selectionReason,
        String historicalEvidence,
        int historicalRuns,
        int historicalFailures,
        String suggestedCommand,
        String predictionState // PREDICTED_AT_RISK (distinct from actual CI test execution)
    ) {}

    public record ActualTestExecutionItem(
        String id,
        String testName,
        String testFilePath,
        String outcome, // PASSED | FAILED | SKIPPED | FLAKY
        long durationMs,
        String errorSummary,
        Instant executedAt
    ) {}

    public record PredictionTestsResponse(
        String predictionRunId,
        String reviewId,
        int prNumber,
        String separationNotice,
        List<PredictedTestRiskItem> predictedTestsAtRisk,
        List<ActualTestExecutionItem> actualCiTestResults,
        boolean hasActualCiResults
    ) {}

    public record HistoricalContextResponse(
        String reviewId,
        String repositoryId,
        String repositoryName,
        boolean limitedHistory,
        String availabilityNotice,
        int totalMatchingRecords,
        List<DefectRecordDto> relatedDefects,
        List<ActualTestExecutionItem> recentTestExecutions,
        List<DeploymentRecordDto> relatedDeployments,
        List<IncidentRecordDto> relatedIncidents
    ) {}

    public record DefectRecordDto(
        String id,
        String repositoryId,
        String title,
        String severity,
        String moduleName,
        List<String> filePaths,
        Integer introducedPrNumber,
        String status,
        Instant reportedAt
    ) {}

    public record DeploymentRecordDto(
        String id,
        String repositoryId,
        String serviceName,
        String environment,
        Integer prNumber,
        String commitSha,
        String outcome, // SUCCEEDED | FAILED | ROLLED_BACK
        String rollbackOfDeploymentId,
        boolean changedConfig,
        String summary,
        Instant deployedAt
    ) {}

    public record IncidentRecordDto(
        String id,
        String repositoryId,
        String title,
        String severity,
        String serviceName,
        List<String> affectedModules,
        String relatedDeploymentId,
        Integer relatedPrNumber,
        String status,
        String evidenceNote,
        Instant occurredAt,
        Instant resolvedAt
    ) {}

    public record ProblematicModuleDto(
        String moduleName,
        String repositoryName,
        long defectCount,
        long incidentCount,
        long testFailureCount
    ) {}

    public record TestReliabilityDto(
        String testName,
        String testFilePath,
        long totalRuns,
        long failedRuns,
        long flakyRuns,
        double passRatePct
    ) {}

    public record DeploymentTrendDto(
        String serviceName,
        long totalDeployments,
        long failedDeployments,
        long rollbacks,
        double successRatePct
    ) {}

    public record PredictionSummaryRow(
        String predictionRunId,
        String reviewId,
        String repositoryId,
        String repositoryName,
        int prNumber,
        String prTitle,
        int overallEngineeringRiskScore,
        String overallRiskLevel,
        String bugRiskLevel,
        String testFailureRiskLevel,
        String incidentRiskLevel,
        String changeFailureRiskLevel,
        String performanceRiskLevel,
        int historicalSampleSize,
        String engineVersion,
        Instant createdAt
    ) {}

    public record PredictiveOverviewResponse(
        long totalPredictions,
        long highRiskPredictions,
        long totalHistoricalTestExecutions,
        long totalDeployments,
        long totalRollbacks,
        long totalConfirmedIncidents,
        long totalConfirmedDefects,
        boolean limitedHistoricalData,
        String historicalDataNotice,
        Map<String, Long> riskLevelDistribution,
        List<PredictionSummaryRow> recentPredictions,
        List<ProblematicModuleDto> problematicModules,
        List<TestReliabilityDto> testReliability,
        List<DeploymentTrendDto> deploymentOutcomes,
        List<IncidentRecordDto> recentIncidents,
        List<ModelEvaluationDto> latestEvaluations
    ) {}

    public record ModelEvaluationDto(
        String id,
        String repositoryId,
        String engineVersion,
        String category,
        String status, // EVALUATED | INSUFFICIENT_LABELLED_DATA
        int sampleSize,
        int positiveOutcomes,
        BigDecimal precisionScore,
        BigDecimal recallScore,
        BigDecimal f1Score,
        BigDecimal falsePositiveRate,
        BigDecimal falseNegativeRate,
        BigDecimal calibrationError,
        Map<String, Object> baselineComparison,
        String notes,
        Instant evaluatedAt
    ) {}
}
