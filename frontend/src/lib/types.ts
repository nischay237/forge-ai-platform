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
  tokenType: string;
  user: UserDto;
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
}

export interface ImpactedNode {
  nodeName: string;
  filePath: string;
  hopDistance: number;
  relationship: string;
  isTest: boolean;
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
  summary: string | null;
  promptVersion: string;
  modelUsed: string;
  tokensUsed: number;
  estimatedCostUsd: number;
  processingMs: number;
  publishedToGithub: boolean;
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
  status: 'PENDING' | 'APPROVED' | 'DISMISSED';
  reviewedBy: string | null;
  reviewedAt: string | null;
  createdAt: string;
}

export interface ReviewDetailDto {
  review: ReviewSummaryDto;
  files: Array<{
    filePath: string;
    language: string;
    originalContent: string;
    modifiedContent: string;
    addedCount: number;
    removedCount: number;
  }>;
  findings: ReviewFinding[];
  changeImpacts: ChangeImpactResponse[];
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
  status: 'OPEN' | 'MITIGATED' | 'FALSE_POSITIVE';
  detectedAt: string;
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
  eventType: string;
  resourceId: string;
  stage: string;
  progressPercent: number;
  message: string;
  timestamp: string;
}
