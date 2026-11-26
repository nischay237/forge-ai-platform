import { useForgeStore } from './store';
import type {
  ArchitectureDriftResponse,
  AuditLog,
  AuthResponse,
  ChangeImpactResponse,
  ChangeRiskPolicy,
  CiBuildDto,
  CodeSearchResponse,
  DependencyGraphResponse,
  EvaluationRunDto,
  FindingSuppression,
  HistoricalContextResponse,
  IngestionResultDto,
  OverviewAnalyticsDto,
  PredictionEvidenceResponse,
  PredictionModelEvaluationDto,
  PredictionRunHistoryItem,
  PredictionTestsResponse,
  PredictiveOverviewResponse,
  PrPredictionResponse,
  RepositoryDto,
  ReviewDetailDto,
  ReviewFinding,
  ReviewSummaryDto,
  SecurityFinding,
  SessionResponse,
  SourceFileDetailDto,
  SourceFileSummaryDto,
  TenantDto,
  WebhookSecretRotationResponse,
  WorkspaceSettingsResponse,
} from './types';

export class ApiClientError extends Error {
  status: number;
  code: string;
  fieldErrors: Record<string, string>;

  constructor(status: number, code: string, message: string, fieldErrors: Record<string, string> = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
  }
}

let inFlightRefresh: Promise<boolean> | null = null;

async function tryRefreshSession(): Promise<boolean> {
  if (inFlightRefresh) {
    return inFlightRefresh;
  }
  const rt = useForgeStore.getState().refreshToken;
  if (!rt) {
    return false;
  }
  inFlightRefresh = (async () => {
    try {
      const res = await fetch('/api/v1/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: rt }),
      });
      if (!res.ok) return false;
      const auth = (await res.json()) as AuthResponse;
      useForgeStore.getState().setSession(auth);
      return true;
    } catch {
      return false;
    } finally {
      inFlightRefresh = null;
    }
  })();
  return inFlightRefresh;
}

function safeParseJson(text: string): Record<string, unknown> | null {
  if (!text) return null;
  try {
    return JSON.parse(text) as Record<string, unknown>;
  } catch {
    return null;
  }
}

async function request<T>(path: string, init: RequestInit = {}, allowRefreshRetry = true): Promise<T> {
  const token = useForgeStore.getState().token;
  const headers = new Headers(init.headers);
  if (!headers.has('Content-Type') && init.body) {
    headers.set('Content-Type', 'application/json');
  }
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  const res = await fetch(path, { ...init, headers });

  if (res.status === 401 && !path.includes('/auth/login') && !path.includes('/auth/register') && !path.includes('/auth/refresh')) {
    if (allowRefreshRetry && (await tryRefreshSession())) {
      return request<T>(path, init, false);
    }
    useForgeStore.getState().clearSession();
    throw new ApiClientError(401, 'UNAUTHORIZED', 'Session expired. Please sign in again.');
  }

  if (res.status === 204) {
    return undefined as T;
  }

  const text = await res.text();
  const data = safeParseJson(text);

  if (!res.ok) {
    const fieldErrors =
      data && typeof data.fieldErrors === 'object' && data.fieldErrors !== null
        ? (data.fieldErrors as Record<string, string>)
        : {};
    const fieldDetail = Object.entries(fieldErrors)
      .map(([k, v]) => `${k}: ${v}`)
      .join('; ');
    const baseMsg = typeof data?.message === 'string' ? data.message : `Request failed with status ${res.status}`;
    throw new ApiClientError(
      res.status,
      typeof data?.code === 'string' ? data.code : 'API_ERROR',
      fieldDetail ? `${baseMsg} (${fieldDetail})` : baseMsg,
      fieldErrors
    );
  }

  return data as unknown as T;
}

const enc = encodeURIComponent;

export const api = {
  login: (email: string, password: string) =>
    request<AuthResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),
  register: (payload: { workspaceName: string; fullName: string; email: string; password: string }) =>
    request<AuthResponse>('/api/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  logout: (refreshToken: string | null) =>
    request<void>(
      '/api/v1/auth/logout',
      {
        method: 'POST',
        body: JSON.stringify({ refreshToken }),
      },
      false
    ),
  me: () => request<SessionResponse>('/api/v1/auth/me'),

  issueSseTicket: () =>
    request<{ ticket: string; expiresInSeconds: number }>('/api/v1/stream/ticket', {
      method: 'POST',
    }),

  getOverview: () => request<OverviewAnalyticsDto>('/api/v1/analytics/overview'),

  listRepos: () => request<RepositoryDto[]>('/api/v1/repositories'),
  connectRepo: (payload: {
    owner: string;
    name: string;
    defaultBranch?: string;
    primaryLanguage?: string;
    description?: string;
    fetchFromGithub: boolean;
  }) =>
    request<RepositoryDto>('/api/v1/repositories', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  reindexRepo: (repoId: string) =>
    request<RepositoryDto>(`/api/v1/repositories/${enc(repoId)}/reindex`, { method: 'POST' }),
  deleteRepo: (repoId: string) =>
    request<void>(`/api/v1/repositories/${enc(repoId)}`, { method: 'DELETE' }),
  listRepoFiles: (repoId: string) =>
    request<SourceFileSummaryDto[]>(`/api/v1/repositories/${enc(repoId)}/files`),
  getFileDetail: (repoId: string, fileId: string) =>
    request<SourceFileDetailDto>(`/api/v1/repositories/${enc(repoId)}/files/${enc(fileId)}`),

  getDependencyGraph: (repoId: string) =>
    request<DependencyGraphResponse>(`/api/v1/repositories/${enc(repoId)}/code-intel/graph`),
  getChangeImpact: (repoId: string, target: string) =>
    request<ChangeImpactResponse>(
      `/api/v1/repositories/${enc(repoId)}/code-intel/impact?target=${enc(target)}`
    ),
  getArchitectureDrift: (repoId: string) =>
    request<ArchitectureDriftResponse>(`/api/v1/repositories/${enc(repoId)}/code-intel/drift`),
  searchCode: (repoId: string, q: string) =>
    request<CodeSearchResponse>(`/api/v1/repositories/${enc(repoId)}/code-intel/search?q=${enc(q)}`),

  listReviews: (repositoryId?: string) =>
    request<ReviewSummaryDto[]>(
      repositoryId ? `/api/v1/reviews?repositoryId=${enc(repositoryId)}` : '/api/v1/reviews'
    ),
  triggerReview: (payload: {
    repositoryId: string;
    prNumber: number;
    title: string;
    author?: string;
    sourceBranch: string;
    targetBranch?: string;
    rawDiff: string;
  }) =>
    request<ReviewSummaryDto>('/api/v1/reviews', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  importGithubPr: (prUrl: string) =>
    request<ReviewSummaryDto>('/api/v1/reviews/import-github-pr', {
      method: 'POST',
      body: JSON.stringify({ prUrl }),
    }),
  getReviewDetail: (reviewId: string) => request<ReviewDetailDto>(`/api/v1/reviews/${enc(reviewId)}`),
  decideFinding: (reviewId: string, findingId: string, decision: 'APPROVED' | 'DISMISSED' | 'PENDING') =>
    request<ReviewFinding>(`/api/v1/reviews/${enc(reviewId)}/findings/${enc(findingId)}`, {
      method: 'PATCH',
      body: JSON.stringify({ decision }),
    }),
  publishReview: (reviewId: string) =>
    request<ReviewSummaryDto>(`/api/v1/reviews/${enc(reviewId)}/publish`, { method: 'POST' }),

  listEvaluations: () => request<EvaluationRunDto[]>('/api/v1/reviews/evaluations'),
  runEvaluation: () => request<EvaluationRunDto>('/api/v1/reviews/evaluations/run', { method: 'POST' }),

  listSecurityFindings: (params?: { repositoryId?: string; severity?: string; status?: string }) => {
    const qs = new URLSearchParams();
    if (params?.repositoryId) qs.set('repositoryId', params.repositoryId);
    if (params?.severity) qs.set('severity', params.severity);
    if (params?.status) qs.set('status', params.status);
    const suffix = qs.toString() ? `?${qs.toString()}` : '';
    return request<SecurityFinding[]>(`/api/v1/security/findings${suffix}`);
  },
  triageSecurityFinding: (findingId: string, status: 'OPEN' | 'MITIGATED' | 'FALSE_POSITIVE' | 'SUPPRESSED') =>
    request<SecurityFinding>(`/api/v1/security/findings/${enc(findingId)}`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    }),
  scanRepositorySecurity: (repoId: string) =>
    request<SecurityFinding[]>(`/api/v1/security/repositories/${enc(repoId)}/scan`, { method: 'POST' }),
  exportSbom: (repoId: string) =>
    request<Record<string, unknown>>(`/api/v1/security/repositories/${enc(repoId)}/sbom`),
  exportSarif: (repoId: string) =>
    request<Record<string, unknown>>(`/api/v1/security/repositories/${enc(repoId)}/sarif`),
  listSuppressions: () => request<FindingSuppression[]>('/api/v1/security/suppressions'),
  createSuppression: (payload: {
    repositoryId?: string;
    fingerprint: string;
    ruleId: string;
    reason: string;
  }) =>
    request<FindingSuppression>('/api/v1/security/suppressions', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  deleteSuppression: (id: string) =>
    request<void>(`/api/v1/security/suppressions/${enc(id)}`, { method: 'DELETE' }),

  getPolicy: (repositoryId?: string) =>
    request<ChangeRiskPolicy>(
      repositoryId ? `/api/v1/policies?repositoryId=${enc(repositoryId)}` : '/api/v1/policies'
    ),
  updatePolicy: (payload: Partial<ChangeRiskPolicy>) =>
    request<ChangeRiskPolicy>('/api/v1/policies', {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),

  listCiBuilds: (repositoryId?: string) =>
    request<CiBuildDto[]>(
      repositoryId ? `/api/v1/ci/builds?repositoryId=${enc(repositoryId)}` : '/api/v1/ci/builds'
    ),
  submitCiBuild: (payload: {
    repositoryId: string;
    pipelineName: string;
    branch: string;
    prNumber?: number;
    status?: string;
    rawLog: string;
  }) =>
    request<CiBuildDto>('/api/v1/ci/builds', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  listAuditLogs: (action?: string) =>
    request<AuditLog[]>(action ? `/api/v1/audit-logs?action=${enc(action)}` : '/api/v1/audit-logs'),

  getSettings: () => request<WorkspaceSettingsResponse>('/api/v1/settings'),
  updateSettings: (payload: {
    workspaceName?: string;
    aiProvider?: string;
    promptVersion?: string;
    requireApprovalBeforePublish?: boolean;
    newWebhookSecret?: string;
    newGithubToken?: string;
  }) =>
    request<TenantDto>('/api/v1/settings', {
      method: 'PATCH',
      body: JSON.stringify(payload),
    }),
  rotateWebhookSecret: () =>
    request<WebhookSecretRotationResponse>('/api/v1/settings/rotate-webhook-secret', {
      method: 'POST',
    }),
  getActuatorHealth: () => request<Record<string, unknown>>('/actuator/health'),

  // --- Predictive Engineering Intelligence APIs ---
  getPredictionOverview: () => request<PredictiveOverviewResponse>('/api/v1/predictions/overview'),
  getPrPrediction: (reviewId: string) =>
    request<PrPredictionResponse>(`/api/v1/predictions/${enc(reviewId)}`),
  runPrPrediction: (reviewId: string) =>
    request<PrPredictionResponse>(`/api/v1/predictions/${enc(reviewId)}/run`, { method: 'POST' }),
  listPrPredictionRuns: (reviewId: string) =>
    request<PredictionRunHistoryItem[]>(`/api/v1/predictions/${enc(reviewId)}/runs`),
  getPrPredictionEvidence: (reviewId: string) =>
    request<PredictionEvidenceResponse>(`/api/v1/predictions/${enc(reviewId)}/evidence`),
  getPrPredictionTests: (reviewId: string) =>
    request<PredictionTestsResponse>(`/api/v1/predictions/${enc(reviewId)}/tests`),
  getPrPredictionHistory: (reviewId: string) =>
    request<HistoricalContextResponse>(`/api/v1/predictions/${enc(reviewId)}/history`),
  ingestTestExecution: (payload: {
    repositoryId: string;
    prNumber?: number;
    commitSha?: string;
    testName: string;
    testFilePath?: string;
    moduleName?: string;
    outcome: 'PASSED' | 'FAILED' | 'SKIPPED' | 'FLAKY';
    durationMs?: number;
    errorSummary?: string;
    externalEventKey?: string;
  }) =>
    request<IngestionResultDto>('/api/v1/predictions/history/tests', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  ingestDeployment: (payload: {
    repositoryId: string;
    serviceName: string;
    environment?: string;
    prNumber?: number;
    commitSha?: string;
    outcome: 'SUCCEEDED' | 'FAILED' | 'ROLLED_BACK';
    changedConfig?: boolean;
    summary?: string;
    externalEventKey?: string;
  }) =>
    request<IngestionResultDto>('/api/v1/predictions/history/deployments', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  ingestIncident: (payload: {
    repositoryId: string;
    title: string;
    severity: 'SEV1' | 'SEV2' | 'SEV3' | 'SEV4';
    serviceName: string;
    affectedModules?: string[];
    relatedPrNumber?: number;
    status?: 'OPEN' | 'MITIGATED' | 'RESOLVED';
    evidenceNote?: string;
    externalEventKey?: string;
  }) =>
    request<IngestionResultDto>('/api/v1/predictions/history/incidents', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  ingestDefect: (payload: {
    repositoryId: string;
    title: string;
    severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';
    moduleName?: string;
    filePaths?: string[];
    introducedPrNumber?: number;
    status?: 'OPEN' | 'FIXED';
    externalEventKey?: string;
  }) =>
    request<IngestionResultDto>('/api/v1/predictions/history/defects', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  listPredictionEvaluations: () =>
    request<PredictionModelEvaluationDto[]>('/api/v1/predictions/evaluations'),
  runPredictionEvaluation: (repositoryId?: string) =>
    request<PredictionModelEvaluationDto[]>(
      repositoryId
        ? `/api/v1/predictions/evaluations/run?repositoryId=${enc(repositoryId)}`
        : '/api/v1/predictions/evaluations/run',
      { method: 'POST' }
    ),
};
