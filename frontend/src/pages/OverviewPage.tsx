import type { ComponentType } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import {
  Activity,
  ArrowUpRight,
  Cpu,
  Database,
  FolderGit2,
  Gauge,
  GitPullRequest,
  Layers,
  ShieldAlert,
} from 'lucide-react';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from '../lib/api';
import { useForgeStore } from '../lib/store';
import { Badge, Button, ErrorBanner, PageHeader, Panel, SkeletonBlock } from '../components/ui';
import { formatRelativeTime, mergeDecisionClasses, severityClasses, statusClasses } from '../lib/utils';

export function OverviewPage() {
  const navigate = useNavigate();
  const user = useForgeStore((s) => s.user);
  const isAdmin = user?.role === 'OWNER' || user?.role === 'ADMIN';
  const isViewer = user?.role === 'VIEWER';

  const overview = useQuery({ queryKey: ['overview'], queryFn: api.getOverview });
  const predOverview = useQuery({ queryKey: ['prediction-overview'], queryFn: api.getPredictionOverview });
  const reviews = useQuery({ queryKey: ['reviews'], queryFn: () => api.listReviews() });
  const builds = useQuery({ queryKey: ['ci'], queryFn: () => api.listCiBuilds() });
  const audits = useQuery({
    queryKey: ['audit'],
    queryFn: () => api.listAuditLogs(),
    enabled: isAdmin,
  });

  if (overview.isError) {
    return (
      <ErrorBanner
        message={
          overview.error instanceof Error ? overview.error.message : 'Failed to load workspace overview'
        }
        onRetry={() => overview.refetch()}
      />
    );
  }

  const data = overview.data;

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Change Impact & Risk Control Plane"
        title="Workspace"
        accentWord="overview."
        description="Live code intelligence, deterministic PR risk verdicts (PASS/WARN/BLOCK), security posture, and CI reliability computed from persisted workspace telemetry."
        actions={
          <>
            <Button variant="secondary" onClick={() => navigate('/predictions')}>
              Predictive Forecasts
            </Button>
            <Button variant="secondary" onClick={() => navigate('/code-intel')}>
              Explore AST Graph
            </Button>
            {!isViewer && <Button onClick={() => navigate('/reviews?new=1')}>Run PR Review</Button>}
          </>
        }
      />

      {overview.isLoading || !data ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <SkeletonBlock key={i} className="h-[112px]" />
          ))}
        </div>
      ) : (
        <Panel className="overflow-hidden">
          <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 divide-y sm:divide-y-0 sm:divide-x divide-[#EAEAE4]">
            <KpiCell
              label="Connected Repositories"
              value={String(data.connectedRepositories)}
              meta={`${data.repositoryHealth.reduce((a, r) => a + r.symbols, 0)} AST symbols indexed`}
              icon={FolderGit2}
              onClick={() => navigate('/repositories')}
            />
            <KpiCell
              label="Pull Requests Analyzed"
              value={String(data.totalPrReviews)}
              meta={`${data.totalFindings} findings · avg ${data.avgReviewLatencyMs}ms`}
              icon={GitPullRequest}
              onClick={() => navigate('/reviews')}
            />
            <KpiCell
              label="Open Security Alerts"
              value={String(data.openSecurityIssues)}
              meta="Secrets, Entropy, SAST, IaC & SCA"
              icon={ShieldAlert}
              accent={data.openSecurityIssues > 0 ? 'text-red-700' : 'text-emerald-700'}
              onClick={() => navigate('/security')}
            />
            <KpiCell
              label="CI Success · Eval F1"
              value={data.totalCiBuilds === 0 ? '—' : `${data.ciSuccessRatePct}%`}
              meta={
                data.totalCiBuilds === 0
                  ? data.latestEvalF1 != null
                    ? `Eval F1 ${(data.latestEvalF1 * 100).toFixed(1)}% · No builds recorded`
                    : 'No builds recorded yet'
                  : data.latestEvalF1 != null
                    ? `Eval F1 ${(data.latestEvalF1 * 100).toFixed(1)}% · ${data.totalCiBuilds} builds`
                    : `${data.totalCiBuilds} builds recorded`
              }
              icon={Gauge}
              accent={data.totalCiBuilds === 0 ? 'text-stone-400' : 'text-emerald-700'}
              onClick={() => navigate('/analytics')}
            />
          </div>
        </Panel>
      )}

      {data && (
        <Panel className="px-4 py-3 bg-[#FAFAF8] flex flex-wrap items-center justify-between gap-4 text-xs">
          <div className="flex flex-wrap items-center gap-x-5 gap-y-2 text-stone-600">
            <span className="flex items-center gap-1.5 font-mono text-[11px] text-[#111111]">
              <Layers className="w-3.5 h-3.5 text-stone-500" />
              Transport:{' '}
              <strong className="font-semibold text-[#111111]">
                {String(data.infrastructureTelemetry.transport ?? 'outbox')}
              </strong>
            </span>
            <span className="flex items-center gap-1.5 font-mono text-[11px]">
              <Database className="w-3.5 h-3.5 text-emerald-700" />
              Cache:{' '}
              <span className="text-[#111111] font-medium">
                {String(data.infrastructureTelemetry.cacheBackend ?? 'local')}
              </span>
            </span>
            <span className="flex items-center gap-1.5 font-mono text-[11px] tabular-nums">
              <Cpu className="w-3.5 h-3.5 text-stone-500" />
              Tenant Outbox Published:{' '}
              <span className="text-[#111111] font-medium">
                {String(data.infrastructureTelemetry.published ?? 0)}
              </span>
              <span className="text-stone-300">/</span>
              Pending:{' '}
              <span className="text-[#111111] font-medium">
                {String(data.infrastructureTelemetry.pending ?? 0)}
              </span>
              <span className="text-stone-300">/</span>
              DLT:{' '}
              <span className="text-[#111111] font-medium">
                {String(data.infrastructureTelemetry.deadLetter ?? 0)}
              </span>
            </span>
          </div>
          <span className="font-mono text-[11px] text-stone-500 tabular-nums">
            {data.totalTokensUsed.toLocaleString()} tokens analyzed
          </span>
        </Panel>
      )}

      {predOverview.data && (
        <Panel className="px-4 py-3 bg-white flex flex-wrap items-center justify-between gap-3 text-xs">
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <span className="font-mono text-[10.5px] uppercase tracking-[0.1em] px-2 py-0.5 rounded bg-[#111111] text-white">
              Predictive Engine
            </span>
            <span className="text-stone-700">
              <strong className="font-mono text-[#111111]">{predOverview.data.totalPredictions}</strong> PR forecast(s)
              {' · '}
              <strong className="font-mono text-[#111111]">{predOverview.data.highRiskPredictions}</strong> High/Critical
              {' · '}
              <strong className="font-mono text-[#111111]">{predOverview.data.totalDeployments}</strong> deploy(s) (
              <strong className="font-mono text-[#111111]">{predOverview.data.totalRollbacks}</strong> rollback(s))
              {' · '}
              <strong className="font-mono text-[#111111]">{predOverview.data.totalConfirmedIncidents}</strong> incident(s)
            </span>
          </div>
          <Link
            to="/predictions"
            className="text-xs font-medium text-[#111111] hover:underline inline-flex items-center gap-1"
          >
            Open Predictive Intelligence <ArrowUpRight className="w-3.5 h-3.5" />
          </Link>
        </Panel>
      )}

      <div className="grid grid-cols-1 xl:grid-cols-3 gap-5">
        <Panel className="xl:col-span-2 overflow-hidden">
          <div className="px-5 py-3.5 border-b border-[#EFEFEA] bg-[#FAFAF8] flex items-center justify-between">
            <div>
              <h2 className="text-sm font-semibold text-[#111111]">Recent Pull Request Reviews</h2>
              <p className="text-[11px] text-stone-500">
                Deterministic Change Risk verdicts with AST impact &amp; contract analysis
              </p>
            </div>
            <Link
              to="/reviews"
              className="text-xs font-medium text-[#111111] hover:underline flex items-center gap-1"
            >
              View all <ArrowUpRight className="w-3.5 h-3.5" />
            </Link>
          </div>
          <div className="divide-y divide-[#EFEFEA]">
            {reviews.isError ? (
              <div className="p-4">
                <ErrorBanner
                  message={reviews.error instanceof Error ? reviews.error.message : 'Could not load reviews'}
                  onRetry={() => reviews.refetch()}
                />
              </div>
            ) : reviews.isLoading ? (
              <div className="p-4 space-y-2.5">
                <SkeletonBlock className="h-14" />
                <SkeletonBlock className="h-14" />
              </div>
            ) : (reviews.data ?? []).length === 0 ? (
              <div className="p-6 text-center text-xs text-stone-500">
                No pull request reviews recorded yet. Import a GitHub PR or submit a diff to begin.
              </div>
            ) : (
              (reviews.data ?? []).slice(0, 5).map((r) => (
                <Link
                  key={r.id}
                  to={`/reviews/${r.id}`}
                  className="px-5 py-3.5 flex items-center justify-between gap-4 hover:bg-[#FAFAF8] transition-colors"
                >
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="font-mono text-xs font-semibold text-[#111111]">
                        #{r.prNumber}
                      </span>
                      <span className="text-[13px] font-medium text-[#111111] truncate">
                        {r.title}
                      </span>
                    </div>
                    <div className="flex flex-wrap items-center gap-2 mt-1 text-[11px] text-stone-500 font-mono">
                      <span>{r.repositoryName}</span>
                      <span>·</span>
                      <span>{r.sourceBranch}</span>
                      <span>·</span>
                      <span>{r.processingMs}ms</span>
                      <span>·</span>
                      <span>{formatRelativeTime(r.createdAt)}</span>
                    </div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    {r.criticalCount > 0 && (
                      <Badge className={severityClasses('CRITICAL')}>{r.criticalCount} crit</Badge>
                    )}
                    {r.mergeDecision && (
                      <Badge className={mergeDecisionClasses(r.mergeDecision)}>{r.mergeDecision}</Badge>
                    )}
                    <span className="font-mono text-xs font-semibold text-[#111111] tabular-nums w-12 text-right">
                      {r.riskScore}/100
                    </span>
                  </div>
                </Link>
              ))
            )}
          </div>
        </Panel>

        <Panel className="p-5 flex flex-col justify-between">
          <div>
            <h2 className="text-sm font-semibold text-[#111111]">Findings by Severity</h2>
            <p className="text-[11px] text-stone-500 mb-4">
              Persisted PR review &amp; Security Center counts
            </p>
          </div>
          <div className="h-[220px] w-full">
            {data ? (
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={data.findingsBySeverity} barGap={4}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#EAEAE4" vertical={false} />
                  <XAxis
                    dataKey="severity"
                    tick={{ fill: '#78716C', fontSize: 11, fontFamily: 'JetBrains Mono' }}
                    axisLine={false}
                    tickLine={false}
                  />
                  <YAxis
                    allowDecimals={false}
                    tick={{ fill: '#78716C', fontSize: 11, fontFamily: 'JetBrains Mono' }}
                    axisLine={false}
                    tickLine={false}
                    width={28}
                  />
                  <Tooltip
                    contentStyle={{
                      backgroundColor: '#FFFFFF',
                      border: '1px solid #E5E5E0',
                      borderRadius: 8,
                      fontSize: 12,
                      color: '#111111',
                      boxShadow: '0 4px 12px rgba(0,0,0,0.06)',
                    }}
                  />
                  <Bar dataKey="reviewFindings" name="PR Findings" fill="#111111" radius={[3, 3, 0, 0]} />
                  <Bar
                    dataKey="securityFindings"
                    name="Security Alerts"
                    fill="#DC2626"
                    radius={[3, 3, 0, 0]}
                  />
                </BarChart>
              </ResponsiveContainer>
            ) : (
              <SkeletonBlock className="h-full" />
            )}
          </div>
          <div className="flex items-center gap-4 pt-3 border-t border-[#EFEFEA] text-[11px] font-mono text-stone-500">
            <span className="flex items-center gap-1.5">
              <span className="w-2.5 h-2.5 rounded-xs bg-[#111111]" /> PR Findings
            </span>
            <span className="flex items-center gap-1.5">
              <span className="w-2.5 h-2.5 rounded-xs bg-[#DC2626]" /> Security Alerts
            </span>
          </div>
        </Panel>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-5">
        <Panel className="overflow-hidden">
          <div className="px-5 py-3.5 border-b border-[#EFEFEA] bg-[#FAFAF8] flex items-center justify-between">
            <h2 className="text-sm font-semibold text-[#111111] flex items-center gap-2">
              <Activity className="w-4 h-4 text-stone-500" />
              CI Pipeline Diagnostics
            </h2>
            <Link to="/ci" className="text-xs font-medium text-[#111111] hover:underline">
              Investigate logs
            </Link>
          </div>
          <div className="divide-y divide-[#EFEFEA]">
            {builds.isError ? (
              <div className="p-4">
                <ErrorBanner
                  message={builds.error instanceof Error ? builds.error.message : 'Failed to load CI builds'}
                  onRetry={() => builds.refetch()}
                />
              </div>
            ) : (builds.data ?? []).length === 0 ? (
              <div className="p-5 text-xs text-stone-500">
                No CI pipeline builds recorded yet. Submit a build log in CI Intelligence to diagnose failures.
              </div>
            ) : (
              (builds.data ?? []).slice(0, 4).map((b) => (
                <Link
                  key={b.id}
                  to="/ci"
                  className="px-5 py-3 flex items-center justify-between gap-3 hover:bg-[#FAFAF8] transition-colors"
                >
                  <div className="min-w-0">
                    <div className="text-xs font-medium text-[#111111] truncate">{b.pipelineName}</div>
                    <div className="text-[11px] text-stone-500 font-mono truncate mt-0.5">
                      {b.repositoryName} · {b.branch} · {b.durationSeconds}s
                    </div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    {b.failureCategory && b.failureCategory !== 'NONE' && (
                      <span className="text-[10px] font-mono text-amber-800 bg-amber-50 border border-amber-200 px-1.5 py-0.5 rounded">
                        {b.failureCategory}
                      </span>
                    )}
                    <Badge className={statusClasses(b.status)}>{b.status}</Badge>
                  </div>
                </Link>
              ))
            )}
          </div>
        </Panel>

        <Panel className="overflow-hidden">
          <div className="px-5 py-3.5 border-b border-[#EFEFEA] bg-[#FAFAF8] flex items-center justify-between">
            <h2 className="text-sm font-semibold text-[#111111]">Recent Workspace Activity</h2>
            {isAdmin && (
              <Link to="/audit" className="text-xs font-medium text-[#111111] hover:underline">
                Full audit log
              </Link>
            )}
          </div>
          <div className="divide-y divide-[#EFEFEA]">
            {!isAdmin ? (
              <div className="p-5 text-xs text-stone-500">
                Audit trail visibility is restricted to workspace OWNER and ADMIN roles.
              </div>
            ) : audits.isError ? (
              <div className="p-4">
                <ErrorBanner
                  message={audits.error instanceof Error ? audits.error.message : 'Failed to load audit logs'}
                  onRetry={() => audits.refetch()}
                />
              </div>
            ) : (audits.data ?? []).length === 0 ? (
              <div className="p-5 text-xs text-stone-500">
                No recent workspace audit events recorded yet.
              </div>
            ) : (
              (audits.data ?? []).slice(0, 5).map((a) => (
                <div key={a.id} className="px-5 py-3 flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="font-mono text-[11px] font-semibold text-[#111111]">
                        {a.action}
                      </span>
                      <span className="text-[11px] text-stone-500">{a.actorEmail}</span>
                    </div>
                    <p className="text-xs text-stone-600 truncate mt-0.5">{a.details}</p>
                  </div>
                  <span className="text-[11px] font-mono text-stone-500 shrink-0">
                    {formatRelativeTime(a.createdAt)}
                  </span>
                </div>
              ))
            )}
          </div>
        </Panel>
      </div>
    </div>
  );
}

function KpiCell({
  label,
  value,
  meta,
  icon: Icon,
  accent = 'text-[#111111]',
  onClick,
}: {
  label: string;
  value: string;
  meta: string;
  icon: ComponentType<{ className?: string }>;
  accent?: string;
  onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      className="text-left p-5 bg-white hover:bg-[#FAFAF8] transition-colors cursor-pointer group"
    >
      <div className="flex items-center justify-between mb-2.5">
        <span className="text-[11px] font-mono uppercase tracking-[0.08em] text-stone-500">
          {label}
        </span>
        <Icon className="w-4 h-4 text-stone-400 group-hover:text-[#111111] transition-colors" />
      </div>
      <div className={`text-2xl sm:text-[28px] font-semibold tracking-[-0.03em] tabular-nums ${accent}`}>
        {value}
      </div>
      <div className="text-[11px] text-stone-500 mt-1.5 font-mono truncate">{meta}</div>
    </button>
  );
}
