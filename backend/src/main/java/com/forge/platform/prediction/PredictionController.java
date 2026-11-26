package com.forge.platform.prediction;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/predictions")
@Tag(name = "Predictive Intelligence", description = "Explainable bug, test failure, incident, deployment change-failure, and static performance predictions")
public class PredictionController {

    private final PredictionPipelineService predictionPipelineService;
    private final HistoricalDataIngestionService historicalIngestionService;
    private final PredictionEvaluationService evaluationService;

    public PredictionController(
        PredictionPipelineService predictionPipelineService,
        HistoricalDataIngestionService historicalIngestionService,
        PredictionEvaluationService evaluationService
    ) {
        this.predictionPipelineService = predictionPipelineService;
        this.historicalIngestionService = historicalIngestionService;
        this.evaluationService = evaluationService;
    }

    @GetMapping("/overview")
    @Operation(summary = "Workspace predictive engineering overview, historical trends, hotspots & evaluations")
    public PredictionDtos.PredictiveOverviewResponse overview() {
        return predictionPipelineService.getOverview();
    }

    @GetMapping("/{pullRequestId}")
    @Operation(summary = "Get or compute explainable predictive risk assessment for a pull request review")
    public PredictionDtos.PrPredictionResponse getPrediction(@PathVariable String pullRequestId) {
        return predictionPipelineService.getOrComputePrediction(pullRequestId);
    }

    @PostMapping("/{pullRequestId}/run")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','REVIEWER')")
    @Operation(summary = "Recompute predictive risk baseline for a pull request review")
    public PredictionDtos.PrPredictionResponse runPrediction(@PathVariable String pullRequestId) {
        return predictionPipelineService.runPrediction(pullRequestId);
    }

    @GetMapping("/{pullRequestId}/runs")
    @Operation(summary = "List versioned prediction runs for a pull request review")
    public List<PredictionDtos.PredictionRunHistoryItem> listRuns(@PathVariable String pullRequestId) {
        return predictionPipelineService.listPredictionRuns(pullRequestId);
    }

    @GetMapping("/{pullRequestId}/evidence")
    @Operation(summary = "Get supporting evidence and risk breakdown by file, class, method, module, and service")
    public PredictionDtos.PredictionEvidenceResponse getEvidence(@PathVariable String pullRequestId) {
        return predictionPipelineService.getPredictionEvidence(pullRequestId);
    }

    @GetMapping("/{pullRequestId}/tests")
    @Operation(summary = "Get predicted tests at risk strictly separated from actual confirmed CI test results")
    public PredictionDtos.PredictionTestsResponse getTests(@PathVariable String pullRequestId) {
        return predictionPipelineService.getPredictionTests(pullRequestId);
    }

    @GetMapping("/{pullRequestId}/history")
    @Operation(summary = "Get real historical defects, test runs, deployments/rollbacks, and incidents relevant to this PR")
    public PredictionDtos.HistoricalContextResponse getHistory(@PathVariable String pullRequestId) {
        return predictionPipelineService.getPredictionHistory(pullRequestId);
    }

    // --- Historical Engineering Outcome Ingestion (Idempotent & RBAC-Protected) ---

    @PostMapping("/history/tests")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','REVIEWER')")
    @Operation(summary = "Ingest an actual CI test execution result (idempotent)")
    public ResponseEntity<PredictionDtos.IngestionResultDto> ingestTestExecution(
        @Valid @RequestBody PredictionDtos.IngestTestExecutionRequest request
    ) {
        PredictionDtos.IngestionResultDto res = historicalIngestionService.ingestTestExecution(request);
        return ResponseEntity.status(res.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(res);
    }

    @PostMapping("/history/deployments")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','REVIEWER')")
    @Operation(summary = "Ingest an actual deployment or rollback record (idempotent)")
    public ResponseEntity<PredictionDtos.IngestionResultDto> ingestDeployment(
        @Valid @RequestBody PredictionDtos.IngestDeploymentRequest request
    ) {
        PredictionDtos.IngestionResultDto res = historicalIngestionService.ingestDeployment(request);
        return ResponseEntity.status(res.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(res);
    }

    @PostMapping("/history/incidents")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','REVIEWER')")
    @Operation(summary = "Ingest a confirmed production incident record (idempotent)")
    public ResponseEntity<PredictionDtos.IngestionResultDto> ingestIncident(
        @Valid @RequestBody PredictionDtos.IngestIncidentRequest request
    ) {
        PredictionDtos.IngestionResultDto res = historicalIngestionService.ingestIncident(request);
        return ResponseEntity.status(res.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(res);
    }

    @PostMapping("/history/defects")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','REVIEWER')")
    @Operation(summary = "Ingest a confirmed defect/bug record (idempotent)")
    public ResponseEntity<PredictionDtos.IngestionResultDto> ingestDefect(
        @Valid @RequestBody PredictionDtos.IngestDefectRequest request
    ) {
        PredictionDtos.IngestionResultDto res = historicalIngestionService.ingestDefect(request);
        return ResponseEntity.status(res.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(res);
    }

    // --- Chronological Held-Out Model Evaluation ---

    @GetMapping("/evaluations")
    @Operation(summary = "List chronological held-out prediction evaluation runs")
    public List<PredictionDtos.ModelEvaluationDto> listEvaluations() {
        return evaluationService.listEvaluations();
    }

    @PostMapping("/evaluations/run")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','REVIEWER')")
    @Operation(summary = "Execute chronological held-out evaluation against confirmed outcomes")
    public List<PredictionDtos.ModelEvaluationDto> runEvaluation(
        @RequestParam(required = false) String repositoryId
    ) {
        return evaluationService.runChronologicalEvaluation(repositoryId);
    }
}
