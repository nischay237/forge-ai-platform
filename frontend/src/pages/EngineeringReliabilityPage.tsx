import { Link } from 'react-router-dom';
import { ArrowLeft, ExternalLink, RefreshCw } from 'lucide-react';
import {
  formatEvidenceTimestamp,
  GITHUB_ACTIONS_URL,
  GITHUB_BLOB_BASE,
  GITHUB_REPO_URL,
  useEngineeringEvidence,
  type EngineeringEvidenceSnapshot,
} from '../lib/engineeringEvidence';
import { useForgeStore } from '../lib/store';
import { EvidenceStatusPill } from '../components/EngineeringStatus';
import { Button, ForgeMark, Panel } from '../components/ui';

const REFERENCE_CONTROLS = [
  {
    area: 'Architecture & Boundaries',
    mechanism: 'Modular monolith enforced by ArchUnit (`common`, `audit`, `codeintel`, `review`, `prediction` isolation; no unscoped `SourceFileJpaRepository.findById`)',
    path: 'backend/src/test/java/com/forge/platform/ArchitectureBoundaryTest.java',
  },
  {
    area: 'CI/CD Pipeline',
    mechanism: '4-job GitHub Actions workflow with least-privilege `contents: read` permissions and 40-char SHA-pinned actions',
    path: '.github/workflows/ci.yml',
  },
  {
    area: 'Database & Migrations',
    mechanism: 'PostgreSQL 16 Flyway V1–V6 schema verified via `PostgresMigrationContainersIT` in Linux CI; optional PostgreSQL Row-Level Security script',
    path: 'docs/postgres-rls.sql',
  },
  {
    area: 'Security & Tenancy',
    mechanism: 'Fail-fast `ProductionSecurityValidator`, AES-256-GCM secrets with tenant AAD & key rotation, `PESSIMISTIC_WRITE` rotating refresh tokens, fail-closed SSE tickets',
    path: 'docs/SECURITY.md',
  },
  {
    area: 'Async Reliability & Recovery',
    mechanism: 'Transactional Outbox with atomic SQL row claiming (`tryClaimEvent`), Kafka `@RetryableTopic` + DLT routing, and DB-backed SSE snapshot recovery across restarts',
    path: 'docs/RUNBOOK.md',
  },
  {
    area: 'Container & Runtime Configuration',
    mechanism: 'Multi-stage non-root Java 21 JRE image (`exec java` PID 1 graceful shutdown), `/actuator/health/readiness` & `/liveness` probes, hardened Nginx headers',
    path: 'Dockerfile',
  },
];

const UNVERIFIED_DIMENSIONS = [
  {
    dimension: 'Code Coverage Percentage (Line / Branch)',
    reason: 'No JaCoCo or Istanbul coverage gate is published as a live artifact; FORGE does not report unmeasured percentages.',
  },
  {
    dimension: 'Multi-Month Uptime / Availability SLA',
    reason: 'No external uptime monitor or multi-month production SLA ledger is connected to this client.',
  },
  {
    dimension: 'Production Load & Latency Benchmarks (QPS / p99)',
    reason: 'No multi-node stress test or live APM trace stream is published here.',
  },
  {
    dimension: 'Live External Production Deployment State',
    reason: 'Container image builds are checked in CI; live hosted infrastructure state is only verified when its own `/actuator/health` responds on the active origin.',
  },
  {
    dimension: 'Trained Supervised ML Model Accuracy',
    reason: 'Predictive scores use deterministic baselines (`DETERMINISTIC_BASELINE_V1`); held-out evaluation requires real confirmed post-prediction outcomes.',
  },
];

export function EngineeringReliabilityPage({
  evidenceOverride,
}: {
  evidenceOverride?: EngineeringEvidenceSnapshot | null;
}) {
  const token = useForgeStore((s) => s.token);
  const { data, loading, refresh } = useEngineeringEvidence(evidenceOverride);

  const shortSha = data?.ciRun.commitSha ? data.ciRun.commitSha.slice(0, 7) : null;
  const commitUrl = data?.ciRun.commitSha
    ? `${GITHUB_REPO_URL}/commit/${data.ciRun.commitSha}`
    : GITHUB_REPO_URL;

  return (
    <div className="min-h-screen bg-[#FAFAF8] text-[#111111]">
      <header className="sticky top-0 z-30 bg-white/95 backdrop-blur-md border-b border-[#E5E5E0]">
        <div className="max-w-[1080px] mx-auto px-5 sm:px-8 h-14 flex items-center justify-between gap-4">
          <div className="flex items-center gap-3 min-w-0">
            <Link to={token ? '/' : '/login'} className="flex items-center gap-2 text-[#111111]">
              <ForgeMark className="w-6 h-6" />
              <span className="font-semibold text-[15px] tracking-[-0.03em]">Forge</span>
            </Link>
            <span className="text-stone-300">/</span>
            <span className="text-xs font-mono text-stone-600 truncate">Engineering &amp; Reliability</span>
          </div>

          <div className="flex items-center gap-3 text-xs">
            <Link to="/showcase" className="text-stone-600 hover:text-[#111111]">
              Product Overview
            </Link>
            <a
              href={GITHUB_REPO_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="text-stone-600 hover:text-[#111111] inline-flex items-center gap-1"
            >
              GitHub <ExternalLink className="w-3 h-3" />
            </a>
            <Link
              to={token ? '/' : '/login'}
              className="inline-flex items-center gap-1 text-[#111111] font-medium hover:underline"
            >
              <ArrowLeft className="w-3 h-3" />
              {token ? 'Workspace' : 'Sign In'}
            </Link>
          </div>
        </div>
      </header>

      <main className="max-w-[1080px] mx-auto px-5 sm:px-8 py-9 space-y-8">
        {/* Title & Refresh Bar */}
        <div className="flex flex-col sm:flex-row sm:items-end justify-between gap-4 pb-5 border-b border-[#E5E5E0]">
          <div className="space-y-1.5">
            <div className="text-[11px] font-mono uppercase tracking-[0.12em] text-stone-500">
              Engineering &amp; Operational Transparency
            </div>
            <h1 className="text-2xl font-semibold tracking-[-0.03em] text-[#111111]">
              Engineering &amp; Reliability
            </h1>
            <p className="text-xs text-stone-600 max-w-2xl leading-relaxed">
              Live runtime probe results, latest <code className="font-mono">main</code> branch CI workflow status,
              architecture and security source references, and explicit disclosure of unverified metrics.
            </p>
          </div>

          <div className="flex items-center gap-3 shrink-0">
            <span className="text-[11px] font-mono text-stone-500">
              Checked: {data ? formatEvidenceTimestamp(data.fetchedAt) : loading ? 'Checking...' : 'Not verified'}
            </span>
            <Button
              type="button"
              size="sm"
              variant="secondary"
              disabled={loading}
              onClick={() => refresh(true)}
            >
              <RefreshCw className="w-3.5 h-3.5" />
              {loading ? 'Refreshing...' : 'Refresh'}
            </Button>
          </div>
        </div>

        {/* 1. Live Verification Summary */}
        <section aria-labelledby="live-checks-heading" className="space-y-3">
          <div className="flex items-center justify-between gap-2">
            <h2 id="live-checks-heading" className="text-sm font-semibold text-[#111111]">
              Live Runtime &amp; CI Verification
            </h2>
            <a
              href={data?.ciRun.htmlUrl || GITHUB_ACTIONS_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="text-[11px] font-mono text-stone-600 hover:text-[#111111] inline-flex items-center gap-1 underline underline-offset-2"
            >
              Inspect GitHub Actions <ExternalLink className="w-3 h-3" />
            </a>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <Panel className="p-4 space-y-2.5">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-semibold text-[#111111]">Same-Origin Backend Health Probe</span>
                <EvidenceStatusPill status={data?.runtimeHealth.status ?? 'unverified'} />
              </div>
              <p className="text-xs text-stone-600 leading-relaxed">
                {data?.runtimeHealth.detail ?? 'Waiting for /actuator/health response...'}
              </p>
              <div className="pt-2 border-t border-[#F0F0EC] flex items-center justify-between text-[11px] font-mono text-stone-500">
                <span>Observed: {formatEvidenceTimestamp(data?.runtimeHealth.observedAt)}</span>
                <span>GET /actuator/health</span>
              </div>
            </Panel>

            <Panel className="p-4 space-y-2.5">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-semibold text-[#111111]">Latest main Branch CI Workflow</span>
                <EvidenceStatusPill status={data?.ciRun.status ?? 'unverified'} />
              </div>
              <p className="text-xs text-stone-600 leading-relaxed">
                {data?.ciRun.detail ?? 'Checking latest GitHub Actions run on main...'}
              </p>
              <div className="pt-2 border-t border-[#F0F0EC] flex flex-wrap items-center justify-between gap-2 text-[11px] font-mono text-stone-500">
                <span>Updated: {formatEvidenceTimestamp(data?.ciRun.observedAt)}</span>
                <div className="flex items-center gap-3">
                  {shortSha && (
                    <a
                      href={commitUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="text-stone-700 hover:text-[#111111] underline underline-offset-2"
                    >
                      commit {shortSha}
                    </a>
                  )}
                  <a
                    href={data?.ciRun.htmlUrl || GITHUB_ACTIONS_URL}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-stone-700 hover:text-[#111111] inline-flex items-center gap-1 underline underline-offset-2"
                  >
                    Workflow Run <ExternalLink className="w-2.5 h-2.5" />
                  </a>
                </div>
              </div>
            </Panel>
          </div>

          {/* Per-Job CI Table */}
          <Panel className="overflow-hidden">
            <div className="px-4 py-3 border-b border-[#EFEFEA] bg-[#FAFAF8] flex items-center justify-between">
              <span className="text-xs font-semibold text-[#111111]">CI Job Breakdown (Real GitHub Actions Jobs)</span>
              <span className="text-[10.5px] font-mono text-stone-500">
                Stale threshold: &gt; 14 days
              </span>
            </div>
            {data && data.ciRun.jobs.length > 0 ? (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-xs">
                  <thead className="border-b border-[#EFEFEA] bg-white text-[10.5px] font-mono uppercase text-stone-500">
                    <tr>
                      <th className="px-4 py-2.5">Workflow Job</th>
                      <th className="px-4 py-2.5">Status</th>
                      <th className="px-4 py-2.5">Completed</th>
                      <th className="px-4 py-2.5 text-right">Source Log</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-[#EFEFEA]">
                    {data.ciRun.jobs.map((job) => (
                      <tr key={job.id} className="hover:bg-[#FAFAF8]">
                        <td className="px-4 py-3 font-medium text-[#111111]">{job.name}</td>
                        <td className="px-4 py-3">
                          <EvidenceStatusPill status={job.status} />
                        </td>
                        <td className="px-4 py-3 font-mono text-[11px] text-stone-500">
                          {formatEvidenceTimestamp(job.completedAt)}
                        </td>
                        <td className="px-4 py-3 text-right">
                          <a
                            href={job.htmlUrl}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="font-mono text-[11px] text-stone-700 hover:text-[#111111] inline-flex items-center gap-1 underline underline-offset-2"
                          >
                            View job <ExternalLink className="w-3 h-3" />
                          </a>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <div className="px-4 py-5 text-xs text-stone-600 flex flex-wrap items-center justify-between gap-2">
                <span>
                  {loading
                    ? 'Fetching workflow jobs from GitHub...'
                    : 'Individual workflow job results could not be verified from this browser session (status: Not verified).'}
                </span>
                <a
                  href={GITHUB_ACTIONS_URL}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="font-mono text-[11px] text-[#111111] underline underline-offset-2 inline-flex items-center gap-1"
                >
                  Open GitHub Actions <ExternalLink className="w-3 h-3" />
                </a>
              </div>
            )}
          </Panel>
        </section>

        {/* 2. Architecture, Security & Operational References */}
        <section aria-labelledby="controls-heading" className="space-y-3">
          <h2 id="controls-heading" className="text-sm font-semibold text-[#111111]">
            Architecture, Security &amp; Operational Source References
          </h2>
          <Panel className="overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="border-b border-[#EFEFEA] bg-[#FAFAF8] text-[10.5px] font-mono uppercase text-stone-500">
                  <tr>
                    <th className="px-4 py-2.5">Domain</th>
                    <th className="px-4 py-2.5">Implemented Control</th>
                    <th className="px-4 py-2.5 text-right">Source</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#EFEFEA]">
                  {REFERENCE_CONTROLS.map((row) => (
                    <tr key={row.area} className="hover:bg-[#FAFAF8]">
                      <td className="px-4 py-3 font-medium text-[#111111] whitespace-nowrap">{row.area}</td>
                      <td className="px-4 py-3 text-stone-600 leading-relaxed">{row.mechanism}</td>
                      <td className="px-4 py-3 text-right whitespace-nowrap">
                        <a
                          href={`${GITHUB_BLOB_BASE}/${row.path}`}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="font-mono text-[11px] text-stone-700 hover:text-[#111111] inline-flex items-center gap-1 underline underline-offset-2"
                        >
                          {row.path.split('/').pop()} <ExternalLink className="w-3 h-3" />
                        </a>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Panel>
        </section>

        {/* 3. Explicitly Unverified Dimensions */}
        <section aria-labelledby="unverified-heading" className="space-y-3">
          <h2 id="unverified-heading" className="text-sm font-semibold text-[#111111]">
            Unverified &amp; Unmeasured Dimensions
          </h2>
          <Panel className="overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="border-b border-[#EFEFEA] bg-[#FAFAF8] text-[10.5px] font-mono uppercase text-stone-500">
                  <tr>
                    <th className="px-4 py-2.5">Metric / Claim</th>
                    <th className="px-4 py-2.5">Status</th>
                    <th className="px-4 py-2.5">Explanation</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#EFEFEA]">
                  {UNVERIFIED_DIMENSIONS.map((item) => (
                    <tr key={item.dimension}>
                      <td className="px-4 py-3 font-medium text-[#111111]">{item.dimension}</td>
                      <td className="px-4 py-3">
                        <EvidenceStatusPill status="unverified" />
                      </td>
                      <td className="px-4 py-3 text-stone-600 leading-relaxed">{item.reason}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Panel>
        </section>
      </main>
    </div>
  );
}
