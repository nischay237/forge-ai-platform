export interface UserDto {
  id: string;
  tenantId: string;
  email: string;
  fullName: string;
  role: 'OWNER' | 'ADMIN' | 'REVIEWER' | 'VIEWER';
  createdAt: string;
}

export interface TenantDto {
  id: string;
  name: string;
  slug: string;
  plan: string;
  aiProvider: string;
  promptVersion: string;
  requireApprovalBeforePublish: boolean;
  maskedWebhookSecret: string;
  maskedGithubToken: string;
  githubTokenConfigured: boolean;
  createdAt: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: UserDto;
  tenant: TenantDto;
  initialWebhookSecret?: string | null;
}

export interface SessionResponse {
  user: UserDto;
  tenant: TenantDto;
}

export interface WebhookSecretRotationResponse {
  webhookSecret: string;
  tenant: TenantDto;
}

export interface WorkspaceSettingsResponse {
  tenant: TenantDto;
  members: UserDto[];
  activeProfile: string;
  openaiConfigured: boolean;
}

export interface RepositoryDto {
  id: string;
  owner: string;
  name: string;
  fullName: string;
  defaultBranch: string;
  primaryLanguage: string;
  githubUrl: string;
  description: string;
  indexStatus: string;
  fileCount: number;
  symbolCount: number;
  dependencyCount: number;
  webhookConfigured: boolean;
  lastIndexedAt: string | null;
  createdAt: string;
}

export interface SourceFileSummaryDto {
  id: string;
  filePath: string;
  language: string;
  lineCount: number;
  complexityScore: number;
  indexedAt: string;
}

export interface SourceFileDetailDto {
  id: string;
  repositoryId: string;
  filePath: string;
  language: string;
  lineCount: number;
  complexityScore: number;
  content: string;
  indexedAt: string;
}

export interface GraphNode {
  id: string;
  label: string;
  filePath: string;
  layer: string;
  symbolCount: number;
  complexity: number;
  incomingCount: number;
  outgoingCount: number;
}

export interface GraphEdge {
  id: string;
  source: string;
  target: string;
  dependencyType: string;
}

export interface DependencyGraphResponse {
  repositoryId: string;
  repositoryName: string;
  nodes: GraphNode[];
  edges: GraphEdge[];
  circularDependencies?: string[][];
}

export interface ImpactedNode {
  nodeName: string;
  filePath: string;
  hopDistance: number;
  relationship: string;
  isTest: boolean;
  impactReason?: string;
}

export interface ChangeImpactResponse {
  repositoryId: string;
  targetNode: string;
  totalImpacted: number;
  impactedTests: number;
  riskLevel: string;
  impactedNodes: ImpactedNode[];
  directDependencies: string[];
}

export interface ArchitectureDriftViolation {
  ruleId: string;
  severity: string;
  sourceNode: string;
  sourceFile: string;
  sourceLayer: string;
  targetNode: string;
  targetFile: string | null;
  targetLayer: string;
  dependencyType: string;
  explanation: string;
}

export interface ArchitectureDriftResponse {
  repositoryId: string;
  repositoryName: string;
  totalEdgesAnalyzed: number;
  violationCount: number;
  cycleCount: number;
  violations: ArchitectureDriftViolation[];
  circularDependencies: string[][];
}

export interface CodeSearchResponse {
  query: string;
  synthesizedAnswer: string;
  hits: Array<{
    fileId: string;
    filePath: string;
    symbolName: string;
    symbolKind: string;
    lineNumber: number;
    relevanceScore: number;
    snippet: string;
  }>;
}

export interface RiskFactor {
  category: string;
  title: string;
  severity: string;
  riskPoints: number;
  confidence: number;
  evidence: string;
  explanation: string;
  policyRule: string;
}

export interface ChangeRiskAssessment {
  riskScore: number;
  mergeDecision: 'PASS' | 'WARN' | 'BLOCK';
  policyVersion: number;
  decisionSummary: string;
  safetyDisclaimer: string;
  triggeredPolicies: string[];
  factors: RiskFactor[];
  impactedModulesCount: number;
  breakingApiCount: number;
  migrationRiskCount: number;
  criticalSecurityCount: number;
}

export interface SelectedTest {
  testName: string;
  testFilePath: string;
  selectionReason: string;
  relatedChangedFile: string;
  priority: string;
  suggestedCommand: string;
  executionStatus: string;
}

export interface TestSelectionPlan {
  executionStatus: string;
  disclaimer: string;
  prIncludesTestChanges: boolean;
  recommendedCount: number;
  selectedTests: SelectedTest[];
  suggestedCommands: string[];
}

export interface ReviewSummaryDto {
  id: string;
  repositoryId: string;
  repositoryName: string;
  prNumber: number;
  title: string;
  author: string;
  sourceBranch: string;
  targetBranch: string;
  commitSha: string;
  status: string;
  currentStage: string;
  riskScore: number;
  mergeDecision: 'PASS' | 'WARN' | 'BLOCK';
  summary: string | null;
  promptVersion: string;
  modelUsed: string;
  tokensUsed: number;
  estimatedCostUsd: number;
  processingMs: number;
  publishedToGithub: boolean;
  publishStatus: string;
  githubCommentUrl: string | null;
  findingCount: number;
  criticalCount: number;
  highCount: number;
  createdAt: string;
  completedAt: string | null;
}

export interface ReviewFinding {
  id: string;
  reviewId: string;
  filePath: string;
  lineNumber: number;
  category: string;
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';
  confidence: number;
  ruleId: string;
  title: string;
  explanation: string;
  evidence: string | null;
  suggestedPatch: string | null;
  fingerprint?: string | null;
  status: 'PENDING' | 'APPROVED' | 'DISMISSED';
  reviewedBy: string | null;
  reviewedAt: string | null;
  createdAt: string;
}

export interface ReviewDetailDto {
  review: ReviewSummaryDto;
  files: Array<{
    filePath: string;
    changeType?: string;
    language: string;
    originalContent: string;
    modifiedContent: string;
    addedCount: number;
    removedCount: number;
    changedSymbols?: string[];
  }>;
  findings: ReviewFinding[];
  changeImpacts: ChangeImpactResponse[];
  riskAssessment: ChangeRiskAssessment | null;
  testSelectionPlan: TestSelectionPlan | null;
}

export interface SecurityFinding {
  id: string;
  repositoryId: string;
  reviewId: string | null;
  scanner: string;
  ruleId: string;
  cweId: string | null;
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';
  title: string;
  filePath: string;
  lineNumber: number;
  maskedEvidence: string | null;
  remediation: string;
  fingerprint?: string | null;
  status: 'OPEN' | 'MITIGATED' | 'FALSE_POSITIVE' | 'SUPPRESSED';
  detectedAt: string;
  updatedAt: string;
}

export interface FindingSuppression {
  id: string;
  repositoryId: string | null;
  fingerprint: string;
  ruleId: string;
  reason: string;
  suppressedBy: string;
  expiresAt: string | null;
  createdAt: string;
}

export interface ChangeRiskPolicy {
  id: string;
  repositoryId: string | null;
  version: number;
  blockOnCriticalSecurity: boolean;
  blockOnBreakingApi: boolean;
  blockOnDestructiveMigration: boolean;
  blockOnPromptInjection: boolean;
  maxRiskScoreForPass: number;
  maxRiskScoreForWarn: number;
  minTestsForHighImpact: boolean;
  sensitivePaths: string[];
  architectureRules: Array<{
    id: string;
    sourceLayer: string;
    forbiddenTargetLayer: string;
    severity: string;
    description: string;
  }>;
  updatedBy: string;
  updatedAt: string;
}

export interface CiBuildDto {
  id: string;
  repositoryId: string;
  repositoryName: string;
  pipelineName: string;
  branch: string;
  commitSha: string;
  prNumber: number | null;
  status: 'PASSED' | 'FAILED';
  durationSeconds: number;
  failureCategory: string | null;
  rootCauseSummary: string | null;
  remediationSteps: string | null;
  rawLog: string;
  triggeredAt: string;
}

export interface EvaluationRunDto {
  id: string;
  promptVersion: string;
  modelUsed: string;
  datasetSize: number;
  truePositives: number;
  falsePositives: number;
  falseNegatives: number;
  precision: number;
  recall: number;
  f1Score: number;
  falsePositiveRate: number;
  avgLatencyMs: number;
  caseResults: Array<{
    caseId: string;
    name: string;
    expectedRulePrefix: string;
    shouldDetectIssue: boolean;
    detected: boolean;
    triggeredRules: string[];
    outcome: string;
    latencyMs: number;
  }>;
  executedAt: string;
}

export interface OverviewAnalyticsDto {
  connectedRepositories: number;
  totalPrReviews: number;
  totalFindings: number;
  openSecurityIssues: number;
  totalCiBuilds: number;
  ciSuccessRatePct: number;
  avgReviewLatencyMs: number;
  totalTokensUsed: number;
  totalEstimatedCostUsd: number;
  latestEvalF1: number | null;
  latestEvalPrecision: number | null;
  latestEvalRecall: number | null;
  findingsBySeverity: Array<{ severity: string; reviewFindings: number; securityFindings: number }>;
  findingsByCategory: Array<{ category: string; count: number }>;
  reviewLatencySeries: Array<{ prLabel: string; processingMs: number; riskScore: number; tokensUsed: number }>;
  repositoryHealth: Array<{
    repository: string;
    files: number;
    symbols: number;
    dependencies: number;
    reviews: number;
    openSecurity: number;
  }>;
  infrastructureTelemetry: Record<string, string | number>;
}

export interface AuditLog {
  id: string;
  actorEmail: string;
  actorRole: string;
  action: string;
  resourceType: string;
  resourceId: string | null;
  details: string | null;
  ipAddress: string | null;
  createdAt: string;
}

export interface PipelineEvent {
  eventId?: string;
  operationId?: string;
  correlationId?: string;
  sequence?: number;
  eventType: string;
  resourceId: string;
  stage: string;
  stageLabel?: string;
  status?: 'STARTED' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED' | string;
  stageIndex?: number;
  totalStages?: number;
  progressPercent: number;
  message: string;
  details?: Record<string, unknown>;
  timestamp: string;
}

export interface OperationStatusSnapshot {
  operationId: string;
  eventType: string;
  resourceId: string;
  correlationId: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' | string;
  currentStage: string;
  currentStageLabel: string;
  completedStages: number;
  totalStages: number;
  latestSequence: number;
  startedAt: string;
  updatedAt: string;
  completedAt: string | null;
  events: PipelineEvent[];
}

// --- Predictive Engineering Intelligence DTOs ---

export interface SignalChannelScore {
  channel: string;
  label: string;
  rawScore: number;
  deduplicatedContribution: number;
  riskLevel: string;
  signalKind: string;
  summary: string;
}

export interface TargetRiskBreakdown {
  scope: 'FILE' | 'CLASS' | 'METHOD' | 'MODULE' | 'SERVICE' | string;
  name: string;
  filePath: string | null;
  riskLevel: string;
  riskScore: number;
  reason: string;
}

export interface PredictionEvidenceItem {
  id: string;
  signalSource: string;
  evidenceType: string;
  targetRef: string | null;
  summary: string;
  weight: number;
  historicalCount: number;
  observedAt: string | null;
}

export interface PredictionCategoryItem {
  id: string;
  category:
    | 'BUG_RISK'
    | 'TEST_FAILURE_RISK'
    | 'PRODUCTION_INCIDENT_RISK'
    | 'CHANGE_FAILURE_RISK'
    | 'PERFORMANCE_REGRESSION'
    | string;
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL' | 'INSUFFICIENT_DATA' | string;
  riskScore: number;
  probability: number | null;
  confidenceLevel: 'HIGH' | 'MEDIUM' | 'LOW' | 'INSUFFICIENT_DATA' | string;
  insufficientData: boolean;
  historicalSampleSize: number;
  dataFreshnessAt: string | null;
  ruleOrModelVersion: string;
  explanation: string;
  limitations: string;
  recommendedAction: string;
  targetBreakdowns: TargetRiskBreakdown[];
  evidence: PredictionEvidenceItem[];
}

export interface PrPredictionResponse {
  predictionRunId: string;
  reviewId: string;
  repositoryId: string;
  repositoryName: string;
  prNumber: number;
  prTitle: string;
  commitSha: string;
  engineVersion: string;
  modelType: string;
  overallEngineeringRiskScore: number;
  overallRiskLevel: string;
  policyBoundaryNote: string;
  historicalSampleSize: number;
  dataFreshnessAt: string | null;
  dataAvailabilitySummary: string;
  signalChannels: SignalChannelScore[];
  predictions: PredictionCategoryItem[];
  featureSummary: Record<string, unknown>;
  createdAt: string;
}

export interface PredictionEvidenceResponse {
  predictionRunId: string;
  reviewId: string;
  engineVersion: string;
  byFile: TargetRiskBreakdown[];
  byClass: TargetRiskBreakdown[];
  byMethod: TargetRiskBreakdown[];
  byModule: TargetRiskBreakdown[];
  byService: TargetRiskBreakdown[];
  evidence: PredictionEvidenceItem[];
}

export interface PredictedTestRiskItem {
  testName: string;
  testFilePath: string;
  riskLevel: string;
  riskScore: number;
  confidenceLevel: string;
  selectionReason: string;
  historicalEvidence: string;
  historicalRuns: number;
  historicalFailures: number;
  suggestedCommand: string;
  predictionState: string;
}

export interface ActualTestExecutionItem {
  id: string;
  testName: string;
  testFilePath: string | null;
  outcome: 'PASSED' | 'FAILED' | 'SKIPPED' | 'FLAKY' | string;
  durationMs: number;
  errorSummary: string | null;
  executedAt: string;
}

export interface PredictionTestsResponse {
  predictionRunId: string;
  reviewId: string;
  prNumber: number;
  separationNotice: string;
  predictedTestsAtRisk: PredictedTestRiskItem[];
  actualCiTestResults: ActualTestExecutionItem[];
  hasActualCiResults: boolean;
}

export interface DefectRecordDto {
  id: string;
  repositoryId: string;
  title: string;
  severity: string;
  moduleName: string | null;
  filePaths: string[];
  introducedPrNumber: number | null;
  status: string;
  reportedAt: string;
}

export interface DeploymentRecordDto {
  id: string;
  repositoryId: string;
  serviceName: string;
  environment: string;
  prNumber: number | null;
  commitSha: string | null;
  outcome: 'SUCCEEDED' | 'FAILED' | 'ROLLED_BACK' | string;
  rollbackOfDeploymentId: string | null;
  changedConfig: boolean;
  summary: string | null;
  deployedAt: string;
}

export interface IncidentRecordDto {
  id: string;
  repositoryId: string;
  title: string;
  severity: string;
  serviceName: string;
  affectedModules: string[];
  relatedDeploymentId: string | null;
  relatedPrNumber: number | null;
  status: string;
  evidenceNote: string | null;
  occurredAt: string;
  resolvedAt: string | null;
}

export interface HistoricalContextResponse {
  reviewId: string;
  repositoryId: string;
  repositoryName: string;
  limitedHistory: boolean;
  availabilityNotice: string;
  totalMatchingRecords: number;
  relatedDefects: DefectRecordDto[];
  recentTestExecutions: ActualTestExecutionItem[];
  relatedDeployments: DeploymentRecordDto[];
  relatedIncidents: IncidentRecordDto[];
}

export interface PredictionModelEvaluationDto {
  id: string;
  repositoryId: string | null;
  engineVersion: string;
  category: string;
  status: 'EVALUATED' | 'INSUFFICIENT_LABELLED_DATA' | string;
  sampleSize: number;
  positiveOutcomes: number;
  precisionScore: number | null;
  recallScore: number | null;
  f1Score: number | null;
  falsePositiveRate: number | null;
  falseNegativeRate: number | null;
  calibrationError: number | null;
  baselineComparison: Record<string, unknown>;
  notes: string;
  evaluatedAt: string;
}

export interface PredictionSummaryRow {
  predictionRunId: string;
  reviewId: string;
  repositoryId: string;
  repositoryName: string;
  prNumber: number;
  prTitle: string;
  overallEngineeringRiskScore: number;
  overallRiskLevel: string;
  bugRiskLevel: string;
  testFailureRiskLevel: string;
  incidentRiskLevel: string;
  changeFailureRiskLevel: string;
  performanceRiskLevel: string;
  historicalSampleSize: number;
  engineVersion: string;
  createdAt: string;
}

export interface PredictiveOverviewResponse {
  totalPredictions: number;
  highRiskPredictions: number;
  totalHistoricalTestExecutions: number;
  totalDeployments: number;
  totalRollbacks: number;
  totalConfirmedIncidents: number;
  totalConfirmedDefects: number;
  limitedHistoricalData: boolean;
  historicalDataNotice: string;
  riskLevelDistribution: Record<string, number>;
  recentPredictions: PredictionSummaryRow[];
  problematicModules: Array<{
    moduleName: string;
    repositoryName: string;
    defectCount: number;
    incidentCount: number;
    testFailureCount: number;
  }>;
  testReliability: Array<{
    testName: string;
    testFilePath: string;
    totalRuns: number;
    failedRuns: number;
    flakyRuns: number;
    passRatePct: number;
  }>;
  deploymentOutcomes: Array<{
    serviceName: string;
    totalDeployments: number;
    failedDeployments: number;
    rollbacks: number;
    successRatePct: number;
  }>;
  recentIncidents: IncidentRecordDto[];
  latestEvaluations: PredictionModelEvaluationDto[];
}

export interface IngestionResultDto {
  id: string;
  recordType: string;
  duplicate: boolean;
  externalEventKey: string;
  recordedAt: string;
}

export interface PredictionRunHistoryItem {
  predictionRunId: string;
  commitSha: string;
  engineVersion: string;
  modelType: string;
  overallEngineeringRiskScore: number;
  overallRiskLevel: string;
  historicalSampleSize: number;
  createdAt: string;
}
