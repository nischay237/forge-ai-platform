import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, Play } from 'lucide-react';
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { api } from '../lib/api';
import { Badge, Button, ErrorBanner, PageHeader, Panel, SkeletonBlock } from '../components/ui';
import { formatRelativeTime, statusClasses } from '../lib/utils';

export function AnalyticsEvalPage() {
  const [selectedRunId, setSelectedRunId] = useState<string>('');
  const qc = useQueryClient();
  const overview = useQuery({ queryKey: ['overview'], queryFn: api.getOverview });
  const evals = useQuery({ queryKey: ['evaluations'], queryFn: api.listEvaluations });

  const runEvalMutation = useMutation({
    mutationFn: api.runEvaluation,
    onSuccess: (created) => {
      setSelectedRunId(created.id);
      qc.invalidateQueries({ queryKey: ['evaluations'] });
      qc.invalidateQueries({ queryKey: ['overview'] });
      qc.invalidateQueries({ queryKey: ['audit'] });
    },
  });

  const data = overview.data;
  const evalList = evals.data ?? [];
  const activeEval = evalList.find((e) => e.id === selectedRunId) ?? evalList[0];

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Measured Telemetry & Reproducible Deterministic Eval Harness"
        title="Analytics &"
        accentWord="evaluation."
        description="Live workspace metrics queried from persisted records. Run the 12-case deterministic evaluation benchmark covering SAST, secrets, entropy, IaC, API compatibility, SQL migrations, and negative controls."
        actions={
          <Button onClick={() => runEvalMutation.mutate()} disabled={runEvalMutation.isPending}>
            <Play className="w-3.5 h-3.5" />
            {runEvalMutation.isPending ? 'Running 12-Case Suite...' : 'Run Evaluation Benchmark'}
          </Button>
        }
      />

      {overview.isError && (
        <ErrorBanner
          message={overview.error instanceof Error ? overview.error.message : 'Could not load analytics'}
          onRetry={() => overview.refetch()}
        />
      )}
      {runEvalMutation.isError && (
        <ErrorBanner
          message={runEvalMutation.error instanceof Error ? runEvalMutation.error.message : 'Evaluation run failed'}
        />
      )}

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-5">
        <Panel className="p-5">
          <div className="mb-4">
            <h2 className="text-sm font-semibold text-[#111111]">PR Review Risk &amp; Latency Trend</h2>
            <p className="text-[11px] text-stone-500">
              Dual-axis view: Risk Score (0–100, left) vs Processing Latency (ms, right)
            </p>
          </div>
          <div className="h-[240px]">
            {data ? (
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={data.reviewLatencySeries}>
                  <defs>
                    <linearGradient id="riskFillLight" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#111111" stopOpacity={0.14} />
                      <stop offset="95%" stopColor="#111111" stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="#EAEAE4" vertical={false} />
                  <XAxis
                    dataKey="prLabel"
                    tick={{ fill: '#78716C', fontSize: 11, fontFamily: 'JetBrains Mono' }}
                    axisLine={false}
                    tickLine={false}
                  />
                  <YAxis
                    yAxisId="risk"
                    domain={[0, 100]}
                    tick={{ fill: '#111111', fontSize: 11, fontFamily: 'JetBrains Mono' }}
                    axisLine={false}
                    tickLine={false}
                    width={32}
                  />
                  <YAxis
                    yAxisId="latency"
                    orientation="right"
                    tick={{ fill: '#059669', fontSize: 11, fontFamily: 'JetBrains Mono' }}
                    axisLine={false}
                    tickLine={false}
                    width={44}
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
                  <Area
                    yAxisId="risk"
                    type="monotone"
                    dataKey="riskScore"
                    name="Risk Score (/100)"
                    stroke="#111111"
                    fill="url(#riskFillLight)"
                    strokeWidth={2}
                  />
                  <Area
                    yAxisId="latency"
                    type="monotone"
                    dataKey="processingMs"
                    name="Latency (ms)"
                    stroke="#059669"
                    fill="none"
                    strokeWidth={1.8}
                  />
                </AreaChart>
              </ResponsiveContainer>
            ) : (
              <SkeletonBlock className="h-full" />
            )}
          </div>
          <div className="flex items-center gap-4 pt-3 border-t border-[#EFEFEA] text-[11px] font-mono text-stone-500">
            <span className="flex items-center gap-1.5">
              <span className="w-2.5 h-0.5 bg-[#111111]" /> Risk Score (left axis, 0–100)
            </span>
            <span className="flex items-center gap-1.5">
              <span className="w-2.5 h-0.5 bg-[#059669]" /> Latency ms (right axis)
            </span>
          </div>
        </Panel>

        <Panel className="p-5">
          <div className="mb-4">
            <h2 className="text-sm font-semibold text-[#111111]">
              Findings Distribution by Category
            </h2>
            <p className="text-[11px] text-stone-500">
              Breakdown across Security, API Compatibility, DB Migrations, Bug, Performance &amp; Architecture
            </p>
          </div>
          <div className="h-[240px]">
            {data ? (
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={data.findingsByCategory}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#EAEAE4" vertical={false} />
                  <XAxis
                    dataKey="category"
                    tick={{ fill: '#78716C', fontSize: 10, fontFamily: 'JetBrains Mono' }}
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
                  <Bar dataKey="count" name="Findings" fill="#111111" radius={[3, 3, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            ) : (
              <SkeletonBlock className="h-full" />
            )}
          </div>
          <div className="pt-3 border-t border-[#EFEFEA] text-[11px] font-mono text-stone-500">
            Computed directly from persisted workspace review findings
          </div>
        </Panel>
      </div>

      <Panel className="overflow-hidden">
        <div className="px-5 py-4 border-b border-[#EFEFEA] bg-[#FAFAF8] flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold text-[#111111] flex items-center gap-2">
              <CheckCircle2 className="w-4 h-4 text-emerald-700" />
              Deterministic Analysis Evaluation Suite ({activeEval?.datasetSize ?? 12} Cases)
            </h2>
            <p className="text-[11px] text-stone-500 mt-0.5 font-mono">
              {activeEval
                ? `Run: ${activeEval.promptVersion} · ${activeEval.modelUsed} · ${formatRelativeTime(activeEval.executedAt)}`
                : 'Execute benchmark to measure Precision, Recall, F1 & False-Positive Rate'}
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {evalList.length > 1 && (
              <select
                aria-label="Select historical evaluation run"
                value={activeEval?.id ?? ''}
                onChange={(e) => setSelectedRunId(e.target.value)}
                className="h-8 px-2.5 rounded-md bg-white border border-[#DCDCD5] text-xs font-mono text-[#111111]"
              >
                {evalList.map((run, idx) => (
                  <option key={run.id} value={run.id}>
                    Run #{evalList.length - idx} · F1 {(run.f1Score * 100).toFixed(0)}% ({formatRelativeTime(run.executedAt)})
                  </option>
                ))}
              </select>
            )}

            {activeEval && (
              <div className="flex flex-wrap items-center gap-2 text-xs font-mono tabular-nums">
                <Badge className="bg-emerald-50 text-emerald-800 border-emerald-200">
                  Precision {(activeEval.precision * 100).toFixed(1)}%
                </Badge>
                <Badge className="bg-emerald-50 text-emerald-800 border-emerald-200">
                  Recall {(activeEval.recall * 100).toFixed(1)}%
                </Badge>
                <Badge className="bg-[#111111] text-white border-[#111111]">
                  F1 {(activeEval.f1Score * 100).toFixed(1)}%
                </Badge>
                <Badge className="bg-white text-stone-700 border-[#DCDCD5]">
                  FPR {(activeEval.falsePositiveRate * 100).toFixed(1)}%
                </Badge>
                <Badge className="bg-white text-stone-700 border-[#DCDCD5]">
                  {activeEval.avgLatencyMs}ms avg
                </Badge>
              </div>
            )}
          </div>
        </div>

        {evals.isError ? (
          <div className="p-4">
            <ErrorBanner
              message={evals.error instanceof Error ? evals.error.message : 'Failed to load evaluation runs'}
              onRetry={() => evals.refetch()}
            />
          </div>
        ) : activeEval ? (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="border-b border-[#EFEFEA] bg-[#FAFAF8] text-[10.5px] font-mono uppercase text-stone-500">
                <tr>
                  <th className="px-5 py-2.5">Case</th>
                  <th className="px-5 py-2.5">Scenario</th>
                  <th className="px-5 py-2.5">Expected</th>
                  <th className="px-5 py-2.5">Triggered Rules</th>
                  <th className="px-5 py-2.5">Outcome</th>
                  <th className="px-5 py-2.5 text-right">Latency</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[#EFEFEA]">
                {activeEval.caseResults.map((c) => (
                  <tr key={c.caseId} className="hover:bg-[#FAFAF8]">
                    <td className="px-5 py-3 font-mono font-semibold text-[#111111]">{c.caseId}</td>
                    <td className="px-5 py-3 text-[#111111] font-medium">{c.name}</td>
                    <td className="px-5 py-3 font-mono text-stone-600">{c.expectedRulePrefix}</td>
                    <td className="px-5 py-3 font-mono text-[11px] text-stone-600">
                      {c.triggeredRules.length ? c.triggeredRules.join(', ') : '— (clean)'}
                    </td>
                    <td className="px-5 py-3">
                      <Badge className={statusClasses(c.outcome)}>{c.outcome}</Badge>
                    </td>
                    <td className="px-5 py-3 text-right font-mono text-stone-500 tabular-nums">
                      {c.latencyMs}ms
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="p-6 text-xs text-stone-500">No benchmark runs recorded yet.</div>
        )}
      </Panel>
    </div>
  );
}
