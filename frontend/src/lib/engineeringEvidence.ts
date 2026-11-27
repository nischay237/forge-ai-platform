import { useCallback, useEffect, useState } from 'react';

export type EvidenceStatus = 'verified' | 'failed' | 'stale' | 'unverified';

export const GITHUB_REPO_SLUG = 'nischay237/forge-ai-platform';
export const GITHUB_REPO_URL = `https://github.com/${GITHUB_REPO_SLUG}`;
export const GITHUB_BLOB_BASE = `${GITHUB_REPO_URL}/blob/main`;
export const GITHUB_ACTIONS_URL = `${GITHUB_REPO_URL}/actions`;

/** Checks older than 14 days are transparently surfaced as stale rather than freshly verified. */
export const STALE_THRESHOLD_MS = 14 * 24 * 60 * 60 * 1000;
const CACHE_TTL_MS = 5 * 60 * 1000;

export interface EvidenceCheck {
  id: string;
  label: string;
  status: EvidenceStatus;
  detail: string;
  observedAt: string | null;
  sourceUrl: string;
  sourceLabel: string;
}

export interface CiJobEvidence {
  id: number;
  name: string;
  status: EvidenceStatus;
  rawStatus: string;
  rawConclusion: string | null;
  completedAt: string | null;
  htmlUrl: string;
}

export interface EngineeringEvidenceSnapshot {
  fetchedAt: string;
  overallStatus: EvidenceStatus;
  overallLabel: string;
  runtimeHealth: EvidenceCheck;
  ciRun: {
    status: EvidenceStatus;
    runId: number | null;
    commitSha: string | null;
    branch: string;
    observedAt: string | null;
    htmlUrl: string;
    detail: string;
    jobs: CiJobEvidence[];
  };
  categories: {
    architecture: EvidenceCheck;
    cicd: EvidenceCheck;
    testing: EvidenceCheck;
    security: EvidenceCheck;
    deployment: EvidenceCheck;
    reliability: EvidenceCheck;
  };
}

interface GithubWorkflowRunApi {
  id: number;
  status: string;
  conclusion: string | null;
  head_sha?: string;
  head_branch?: string;
  updated_at?: string;
  html_url?: string;
}

interface GithubJobApi {
  id: number;
  name: string;
  status: string;
  conclusion: string | null;
  completed_at?: string | null;
  html_url?: string;
}

let cachedSnapshot: { data: EngineeringEvidenceSnapshot; expiresAt: number } | null = null;

export function clearEngineeringEvidenceCache(): void {
  cachedSnapshot = null;
}

export function classifyWorkflowOutcome(
  rawStatus: string | null | undefined,
  rawConclusion: string | null | undefined,
  timestampIso: string | null | undefined,
  nowMs: number = Date.now()
): EvidenceStatus {
  if (!rawStatus) return 'unverified';
  const normalizedStatus = rawStatus.toLowerCase();
  const normalizedConclusion = (rawConclusion ?? '').toLowerCase();

  if (normalizedStatus !== 'completed') {
    return 'unverified';
  }
  if (
    normalizedConclusion === 'failure' ||
    normalizedConclusion === 'timed_out' ||
    normalizedConclusion === 'cancelled' ||
    normalizedConclusion === 'action_required'
  ) {
    return 'failed';
  }
  if (normalizedConclusion === 'success') {
    if (timestampIso) {
      const ts = new Date(timestampIso).getTime();
      if (!Number.isNaN(ts) && nowMs - ts > STALE_THRESHOLD_MS) {
        return 'stale';
      }
    }
    return 'verified';
  }
  return 'unverified';
}

export function classifyActuatorHealth(
  ok: boolean,
  statusText: string | null | undefined
): EvidenceStatus {
  if (!statusText) return 'unverified';
  const upper = statusText.trim().toUpperCase();
  if (upper === 'DOWN' || upper === 'OUT_OF_SERVICE') return 'failed';
  if (ok && upper === 'UP') return 'verified';
  return 'unverified';
}

export function deriveOverallSummary(
  healthStatus: EvidenceStatus,
  ciStatus: EvidenceStatus
): { status: EvidenceStatus; label: string } {
  if (healthStatus === 'failed' || ciStatus === 'failed') {
    return { status: 'failed', label: 'Verification issue detected' };
  }
  if (healthStatus === 'verified' && ciStatus === 'verified') {
    return { status: 'verified', label: 'System operational · CI verified' };
  }
  if (healthStatus === 'stale' || ciStatus === 'stale') {
    return { status: 'stale', label: 'CI checks stale' };
  }
  if (healthStatus === 'verified' && ciStatus === 'unverified') {
    return { status: 'unverified', label: 'API healthy · CI not verified' };
  }
  if (healthStatus === 'unverified' && ciStatus === 'verified') {
    return { status: 'unverified', label: 'CI verified · Runtime not verified' };
  }
  return { status: 'unverified', label: 'System status: Not verified' };
}

export function formatEvidenceTimestamp(iso: string | null | undefined): string {
  if (!iso) return 'Not verified';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return 'Not verified';
  return d.toISOString().replace('T', ' ').slice(0, 16) + ' UTC';
}

export const DOCKER_JOB_PATTERN = /\bdocker\b|\bcontainer\s+images?\b/i;
export const SECURITY_JOB_PATTERN = /\b(security|hygiene|compose|codeql|sast)\b/i;
export const BACKEND_JOB_PATTERN = /\b(backend|archunit|postgres|testcontainers|junit|spring)\b/i;
export const FRONTEND_JOB_PATTERN = /\b(frontend|vitest|playwright|typecheck)\b/i;

export function findMatchingJobs(
  jobs: CiJobEvidence[],
  pattern: RegExp,
  excludePattern?: RegExp
): CiJobEvidence[] {
  return jobs.filter((j) => pattern.test(j.name) && (!excludePattern || !excludePattern.test(j.name)));
}

function deduplicateJobs(jobs: CiJobEvidence[]): CiJobEvidence[] {
  const seen = new Map<number, CiJobEvidence>();
  for (const job of jobs) {
    if (!seen.has(job.id)) {
      seen.set(job.id, job);
    }
  }
  return Array.from(seen.values());
}

function mergeJobStatuses(matched: CiJobEvidence[]): EvidenceStatus {
  if (matched.length === 0) return 'unverified';
  if (matched.some((j) => j.status === 'failed')) return 'failed';
  if (matched.some((j) => j.status === 'unverified')) return 'unverified';
  if (matched.some((j) => j.status === 'stale')) return 'stale';
  if (matched.every((j) => j.status === 'verified')) return 'verified';
  return 'unverified';
}

function latestCompletedAt(matched: CiJobEvidence[], fallback: string | null): string | null {
  let latestMs = -Infinity;
  let latestIso: string | null = null;
  for (const job of matched) {
    if (!job.completedAt) continue;
    const ms = new Date(job.completedAt).getTime();
    if (!Number.isNaN(ms) && ms > latestMs) {
      latestMs = ms;
      latestIso = job.completedAt;
    }
  }
  return latestIso ?? fallback;
}

function isAbortError(err: unknown, signal?: AbortSignal): boolean {
  if (signal?.aborted) return true;
  if (err instanceof Error && err.name === 'AbortError') return true;
  if (typeof DOMException !== 'undefined' && err instanceof DOMException && err.name === 'AbortError') return true;
  return typeof err === 'object' && err !== null && 'name' in err && (err as { name?: unknown }).name === 'AbortError';
}

function createAbortError(): Error {
  const err = new Error('The engineering evidence request was aborted.');
  err.name = 'AbortError';
  return err;
}

export function buildEvidenceSnapshot(params: {
  nowIso: string;
  nowMs: number;
  healthOk: boolean;
  healthStatusText: string | null;
  healthDetail: string;
  run: GithubWorkflowRunApi | null;
  jobs: GithubJobApi[];
  ciErrorDetail: string | null;
}): EngineeringEvidenceSnapshot {
  const healthStatus = classifyActuatorHealth(params.healthOk, params.healthStatusText);
  const runtimeHealth: EvidenceCheck = {
    id: 'runtime-health',
    label: 'Backend Health Probe',
    status: healthStatus,
    detail: params.healthDetail,
    observedAt: healthStatus === 'unverified' ? null : params.nowIso,
    sourceUrl: `${GITHUB_BLOB_BASE}/docs/RUNBOOK.md`,
    sourceLabel: 'docs/RUNBOOK.md',
  };

  const mappedJobs: CiJobEvidence[] = params.jobs.map((j) => ({
    id: j.id,
    name: j.name,
    status: classifyWorkflowOutcome(j.status, j.conclusion, j.completed_at ?? params.run?.updated_at, params.nowMs),
    rawStatus: j.status,
    rawConclusion: j.conclusion,
    completedAt: j.completed_at ?? null,
    htmlUrl: j.html_url || params.run?.html_url || GITHUB_ACTIONS_URL,
  }));

  const ciStatus = params.run
    ? classifyWorkflowOutcome(params.run.status, params.run.conclusion, params.run.updated_at, params.nowMs)
    : 'unverified';
  const shortSha = params.run?.head_sha ? params.run.head_sha.slice(0, 7) : null;
  const runUrl = params.run?.html_url || GITHUB_ACTIONS_URL;
  const ciObservedAt = params.run?.updated_at ?? null;

  const ciDetail = params.run
    ? ciStatus === 'verified'
      ? `Latest main workflow passed at commit ${shortSha ?? 'unknown'}.`
      : ciStatus === 'stale'
        ? `Latest main workflow passed at ${shortSha ?? 'unknown'}, but check is older than 14 days.`
        : ciStatus === 'failed'
          ? `Latest main workflow did not pass (${params.run.conclusion ?? params.run.status}) at ${shortSha ?? 'unknown'}.`
          : `Latest main workflow is ${params.run.status} (${params.run.conclusion ?? 'pending'}).`
    : params.ciErrorDetail || 'GitHub Actions status could not be verified from this browser session.';

  const securityJobs = findMatchingJobs(mappedJobs, SECURITY_JOB_PATTERN);
  const backendJobs = findMatchingJobs(mappedJobs, BACKEND_JOB_PATTERN, DOCKER_JOB_PATTERN);
  const frontendJobs = findMatchingJobs(mappedJobs, FRONTEND_JOB_PATTERN, DOCKER_JOB_PATTERN);
  const dockerJobs = findMatchingJobs(mappedJobs, DOCKER_JOB_PATTERN);

  const architectureStatus = mergeJobStatuses(backendJobs);
  const testingJobs = deduplicateJobs([...backendJobs, ...frontendJobs]);
  const testingStatus = mergeJobStatuses(testingJobs);
  const securityStatus = mergeJobStatuses(securityJobs);
  const deploymentStatus = mergeJobStatuses(dockerJobs);

  const overall = deriveOverallSummary(healthStatus, ciStatus);

  return {
    fetchedAt: params.nowIso,
    overallStatus: overall.status,
    overallLabel: overall.label,
    runtimeHealth,
    ciRun: {
      status: ciStatus,
      runId: params.run?.id ?? null,
      commitSha: params.run?.head_sha ?? null,
      branch: params.run?.head_branch || 'main',
      observedAt: ciObservedAt,
      htmlUrl: runUrl,
      detail: ciDetail,
      jobs: mappedJobs,
    },
    categories: {
      architecture: {
        id: 'architecture',
        label: 'Architecture',
        status: architectureStatus,
        detail:
          architectureStatus === 'verified'
            ? 'Modular monolith package boundaries verified by ArchUnit in the latest backend CI job(s).'
            : architectureStatus === 'failed'
              ? 'One or more backend/ArchUnit verification jobs failed in the latest CI run.'
              : architectureStatus === 'stale'
                ? 'Backend ArchUnit verification passed, but the latest check is older than 14 days.'
                : 'Modular monolith with ArchUnit rules, Transactional Outbox, Kafka DLT, and PostgreSQL 16 schema (live check not verified).',
        observedAt: latestCompletedAt(backendJobs, ciObservedAt),
        sourceUrl: `${GITHUB_BLOB_BASE}/docs/ARCHITECTURE.md`,
        sourceLabel: 'docs/ARCHITECTURE.md',
      },
      cicd: {
        id: 'cicd',
        label: 'CI/CD Verification',
        status: ciStatus,
        detail: ciDetail,
        observedAt: ciObservedAt,
        sourceUrl: runUrl,
        sourceLabel: shortSha ? `CI run · ${shortSha}` : '.github/workflows/ci.yml',
      },
      testing: {
        id: 'testing',
        label: 'Automated Testing',
        status: testingStatus,
        detail:
          testingStatus === 'verified'
            ? 'Backend JUnit 5, PostgreSQL 16 Testcontainers, TypeScript typecheck, and Vitest passed in CI. Coverage % is not verified.'
            : testingStatus === 'failed'
              ? 'One or more automated backend or frontend test jobs failed in the latest CI run.'
              : testingStatus === 'stale'
                ? 'Automated test jobs passed, but one or more checks are older than 14 days.'
                : 'Automated JUnit 5, Testcontainers, and Vitest suites are configured in CI; live status is not verified here.',
        observedAt: latestCompletedAt(testingJobs, ciObservedAt),
        sourceUrl: `${GITHUB_BLOB_BASE}/.github/workflows/ci.yml`,
        sourceLabel: 'ci.yml',
      },
      security: {
        id: 'security',
        label: 'Security Practices',
        status: securityStatus,
        detail:
          securityStatus === 'verified'
            ? 'Config hygiene gate passed (no hardcoded secrets or localhost prod fallbacks); AES-256-GCM AAD, rotating tokens, fail-closed SSE tickets, and optional RLS documented.'
            : securityStatus === 'failed'
              ? 'Security hygiene or dependency audit gate failed in the latest CI run.'
              : securityStatus === 'stale'
                ? 'Security gate passed, but the latest check is older than 14 days.'
                : 'AES-256-GCM secrets, fail-fast prod validation, and prod npm audit gate are configured; live run status not verified.',
        observedAt: latestCompletedAt(securityJobs, ciObservedAt),
        sourceUrl: `${GITHUB_BLOB_BASE}/docs/SECURITY.md`,
        sourceLabel: 'docs/SECURITY.md',
      },
      deployment: {
        id: 'deployment',
        label: 'Deployment Verification',
        status: deploymentStatus,
        detail:
          deploymentStatus === 'verified'
            ? 'Frontend, backend, and unified Docker images plus Compose config passed in CI. External production host uptime is not verified.'
            : deploymentStatus === 'failed'
              ? 'One or more container image builds failed in the latest CI run.'
              : deploymentStatus === 'stale'
                ? 'Container image builds passed, but the latest check is older than 14 days.'
                : 'Multi-stage Dockerfiles, readiness probes, and Render manifest exist; live build status not verified here.',
        observedAt: latestCompletedAt(dockerJobs, ciObservedAt),
        sourceUrl: `${GITHUB_BLOB_BASE}/Dockerfile`,
        sourceLabel: 'Dockerfile',
      },
      reliability: {
        id: 'reliability',
        label: 'Reliability',
        status: healthStatus,
        detail:
          healthStatus === 'verified'
            ? 'Live /actuator/health endpoint responded UP. Atomic Outbox claiming, Kafka DLT handling, and DB-backed SSE recovery are active.'
            : healthStatus === 'failed'
              ? 'Live /actuator/health endpoint reported a degraded/down state.'
              : 'Runtime health probe not verified from this client; atomic Outbox leases, Kafka DLT, and recovery runbook are documented.',
        observedAt: runtimeHealth.observedAt,
        sourceUrl: `${GITHUB_BLOB_BASE}/docs/RUNBOOK.md`,
        sourceLabel: 'docs/RUNBOOK.md',
      },
    },
  };
}

export async function fetchEngineeringEvidence(options?: {
  forceRefresh?: boolean;
  signal?: AbortSignal;
}): Promise<EngineeringEvidenceSnapshot> {
  if (options?.signal?.aborted) {
    throw createAbortError();
  }

  const nowMs = Date.now();
  if (!options?.forceRefresh && cachedSnapshot && cachedSnapshot.expiresAt > nowMs) {
    return cachedSnapshot.data;
  }

  const nowIso = new Date(nowMs).toISOString();

  const healthPromise = (async (): Promise<{ ok: boolean; statusText: string | null; detail: string }> => {
    try {
      const res = await fetch('/actuator/health', {
        headers: { Accept: 'application/json' },
        signal: options?.signal,
      });

      // Parse JSON body BEFORE checking res.ok because Spring Boot Actuator returns HTTP 503 with {"status":"DOWN"} / {"status":"OUT_OF_SERVICE"}
      let statusText: string | null = null;
      try {
        const data = (await res.json()) as { status?: string };
        statusText = typeof data?.status === 'string' ? data.status.trim() : null;
      } catch (parseErr) {
        if (isAbortError(parseErr, options?.signal)) {
          throw parseErr;
        }
        statusText = null;
      }

      const upperStatus = statusText ? statusText.toUpperCase() : null;
      if (upperStatus === 'DOWN' || upperStatus === 'OUT_OF_SERVICE') {
        return {
          ok: res.ok,
          statusText: upperStatus,
          detail: `Live /actuator/health endpoint responded ${upperStatus} (HTTP ${res.status}).`,
        };
      }

      if (!res.ok) {
        return {
          ok: false,
          statusText,
          detail: `Backend health probe returned HTTP ${res.status}; runtime status not verified.`,
        };
      }

      return {
        ok: true,
        statusText,
        detail:
          upperStatus === 'UP'
            ? 'Live /actuator/health endpoint responded UP.'
            : statusText
              ? `Live /actuator/health endpoint responded ${statusText}.`
              : 'Live /actuator/health responded without a standard status field.',
      };
    } catch (err) {
      if (isAbortError(err, options?.signal)) {
        throw err;
      }
      return {
        ok: false,
        statusText: null,
        detail: 'Local or same-origin /actuator/health endpoint was unreachable from this browser.',
      };
    }
  })();

  const ciPromise = (async (): Promise<{
    run: GithubWorkflowRunApi | null;
    jobs: GithubJobApi[];
    errorDetail: string | null;
  }> => {
    try {
      const runsRes = await fetch(
        `https://api.github.com/repos/${GITHUB_REPO_SLUG}/actions/runs?branch=main&per_page=1`,
        {
          headers: { Accept: 'application/vnd.github+json' },
          signal: options?.signal,
        }
      );
      if (!runsRes.ok) {
        return {
          run: null,
          jobs: [],
          errorDetail: `GitHub Actions API returned HTTP ${runsRes.status}; status not verified.`,
        };
      }
      const runsPayload = (await runsRes.json()) as { workflow_runs?: GithubWorkflowRunApi[] };
      const latestRun = runsPayload.workflow_runs?.[0] ?? null;
      if (!latestRun) {
        return { run: null, jobs: [], errorDetail: 'No workflow runs found on main.' };
      }

      let jobs: GithubJobApi[] = [];
      try {
        const jobsRes = await fetch(
          `https://api.github.com/repos/${GITHUB_REPO_SLUG}/actions/runs/${latestRun.id}/jobs`,
          {
            headers: { Accept: 'application/vnd.github+json' },
            signal: options?.signal,
          }
        );
        if (jobsRes.ok) {
          const jobsPayload = (await jobsRes.json()) as { jobs?: GithubJobApi[] };
          jobs = jobsPayload.jobs ?? [];
        }
      } catch (jobErr) {
        if (isAbortError(jobErr, options?.signal)) {
          throw jobErr;
        }
        // Keep run-level evidence even if job breakdown fetch fails
      }

      return { run: latestRun, jobs, errorDetail: null };
    } catch (err) {
      if (isAbortError(err, options?.signal)) {
        throw err;
      }
      return {
        run: null,
        jobs: [],
        errorDetail: 'GitHub Actions API unreachable or rate-limited; inspect workflow history on GitHub.',
      };
    }
  })();

  const [healthRes, ciRes] = await Promise.all([healthPromise, ciPromise]);

  if (options?.signal?.aborted) {
    throw createAbortError();
  }

  const snapshot = buildEvidenceSnapshot({
    nowIso,
    nowMs,
    healthOk: healthRes.ok,
    healthStatusText: healthRes.statusText,
    healthDetail: healthRes.detail,
    run: ciRes.run,
    jobs: ciRes.jobs,
    ciErrorDetail: ciRes.errorDetail,
  });

  cachedSnapshot = { data: snapshot, expiresAt: nowMs + CACHE_TTL_MS };
  return snapshot;
}

export function useEngineeringEvidence(controlled?: EngineeringEvidenceSnapshot | null) {
  const [data, setData] = useState<EngineeringEvidenceSnapshot | null>(
    controlled !== undefined ? controlled : cachedSnapshot?.data ?? null
  );
  const [loading, setLoading] = useState<boolean>(controlled === undefined && !cachedSnapshot);

  const refresh = useCallback(
    async (forceRefresh = true) => {
      if (controlled !== undefined) return;
      setLoading(true);
      try {
        const snap = await fetchEngineeringEvidence({ forceRefresh });
        setData(snap);
      } catch {
        // Ignore aborted refresh errors
      } finally {
        setLoading(false);
      }
    },
    [controlled]
  );

  useEffect(() => {
    if (controlled !== undefined) {
      setData(controlled);
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    let mounted = true;
    fetchEngineeringEvidence({ signal: controller.signal })
      .then((snap) => {
        if (mounted && !controller.signal.aborted) {
          setData(snap);
          setLoading(false);
        }
      })
      .catch((err) => {
        if (!mounted || isAbortError(err, controller.signal)) {
          return;
        }
        setLoading(false);
      });
    return () => {
      mounted = false;
      controller.abort();
    };
  }, [controlled]);

  return { data, loading, refresh };
}
