import { useForgeStore } from './store';
import type {
  AuditLog,
  AuthResponse,
  ChangeImpactResponse,
  CiBuildDto,
  CodeSearchResponse,
  DependencyGraphResponse,
  EvaluationRunDto,
  OverviewAnalyticsDto,
  RepositoryDto,
  ReviewDetailDto,
  ReviewFinding,
  ReviewSummaryDto,
  SecurityFinding,
  SourceFileDetailDto,
  SourceFileSummaryDto,
  TenantDto,
  WorkspaceSettingsResponse,
} from './types';

export class ApiClientError extends Error {
  status: number;
  code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = useForgeStore.getState().token;
  const headers = new Headers(init.headers);
  if (!headers.has('Content-Type') && init.body) {
    headers.set('Content-Type', 'application/json');
  }
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  const res = await fetch(path, { ...init, headers });
  if (res.status === 401 && !path.includes('/auth/')) {
    useForgeStore.getState().clearSession();
    throw new ApiClientError(401, 'UNAUTHORIZED', 'Session expired. Please sign in again.');
  }
  if (res.status === 204) {
    return undefined as T;
  }
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    throw new ApiClientError(
      res.status,
      data?.code ?? 'API_ERROR',
      data?.message ?? `Request failed with status ${res.status}`
    );
  }
  return data as T;
}

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
  me: () => request<AuthResponse>('/api/v1/auth/me'),

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
    request<RepositoryDto>(`/api/v1/repositories/${repoId}/reindex`, { method: 'POST' }),
  deleteRepo: (repoId: string) =>
    request<void>(`/api/v1/repositories/${repoId}`, { method: 'DELETE' }),
  listRepoFiles: (repoId: string) =>
    request<SourceFileSummaryDto[]>(`/api/v1/repositories/${repoId}/files`),
  getFileDetail: (repoId: string, fileId: string) =>
    request<SourceFileDetailDto>(`/api/v1/repositories/${repoId}/files/${fileId}`),

  getDependencyGraph: (repoId: string) =>
    request<DependencyGraphResponse>(`/api/v1/repositories/${repoId}/code-intel/graph`),
  getChangeImpact: (repoId: string, target: string) =>
    request<ChangeImpactResponse>(
      `/api/v1/repositories/${repoId}/code-intel/impact?target=${encodeURIComponent(target)}`
    ),
  searchCode: (repoId: string, q: string) =>
    request<CodeSearchResponse>(
      `/api/v1/repositories/${repoId}/code-intel/search?q=${encodeURIComponent(q)}`
    ),

  listReviews: (repositoryId?: string) =>
    request<ReviewSummaryDto[]>(
      repositoryId ? `/api/v1/reviews?repositoryId=${encodeURIComponent(repositoryId)}` : '/api/v1/reviews'
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
  getReviewDetail: (reviewId: string) => request<ReviewDetailDto>(`/api/v1/reviews/${reviewId}`),
  decideFinding: (reviewId: string, findingId: string, decision: 'APPROVED' | 'DISMISSED' | 'PENDING') =>
    request<ReviewFinding>(`/api/v1/reviews/${reviewId}/findings/${findingId}`, {
      method: 'PATCH',
      body: JSON.stringify({ decision }),
    }),
  publishReview: (reviewId: string) =>
    request<ReviewSummaryDto>(`/api/v1/reviews/${reviewId}/publish`, { method: 'POST' }),

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
  triageSecurityFinding: (findingId: string, status: 'OPEN' | 'MITIGATED' | 'FALSE_POSITIVE') =>
    request<SecurityFinding>(`/api/v1/security/findings/${findingId}`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    }),
  scanRepositorySecurity: (repoId: string) =>
    request<SecurityFinding[]>(`/api/v1/security/repositories/${repoId}/scan`, { method: 'POST' }),

  listCiBuilds: (repositoryId?: string) =>
    request<CiBuildDto[]>(
      repositoryId ? `/api/v1/ci/builds?repositoryId=${encodeURIComponent(repositoryId)}` : '/api/v1/ci/builds'
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
    request<AuditLog[]>(action ? `/api/v1/audit-logs?action=${encodeURIComponent(action)}` : '/api/v1/audit-logs'),

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
  getActuatorHealth: () => request<Record<string, unknown>>('/actuator/health'),
};
