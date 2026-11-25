import React from 'react';
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
import { Badge, Button, PageHeader, Panel, SkeletonBlock } from '../components/ui';
import { formatRelativeTime, statusClasses } from '../lib/utils';

export function AnalyticsEvalPage() {
  const qc = useQueryClient();
  const overview = useQuery({ queryKey: ['overview'], queryFn: api.getOverview });
  const evals = useQuery({ queryKey: ['evaluations'], queryFn: api.listEvaluations });

  const runEvalMutation = useMutation({
    mutationFn: api.runEvaluation,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['evaluations'] });
      qc.invalidateQueries({ queryKey: ['overview'] });
      qc.invalidateQueries({ queryKey: ['audit'] });
    },
  });

  const data = overview.data;
  const latestEval = evals.data?.[0];

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Measured Telemetry & Reproducible AI Evaluation"
        title="Analytics &"
        accentWord="AI eval."
        description="Every chart below is queried live from persisted PostgreSQL/H2 records. Execute the 8-case AI Review Evaluation Suite to reproduce precision, recall, F1, false-positive rate, and latency."
        actions={
          <Button onClick={() => runEvalMutation.mutate()} disabled={runEvalMutation.isPending}>
            <Play className="w-3.5 h-3.5" />
            {runEvalMutation.isPending ? 'Running 8-Case Suite...' : 'Run Evaluation Benchmark'}
          </Button>
        }
      />

      {/* Charts Grid */}
      <div className="grid grid-cols-1 xl:grid-cols-2 gap-5">
        <Panel className="p-5">
          <div className="mb-4">
            <h2 className="text-sm font-semibold text-[#111111]">PR Review Risk &amp; Latency Trend</h2>
            <p className="text-[11px] text-stone-500">
              Real processing duration (ms) &amp; risk score across analyzed PRs
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
                    tick={{ fill: '#78716C', fontSize: 11, fontFamily: 'JetBrains Mono' }}
                    axisLine={false}
                    tickLine={false}
                    width={34}
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
                    type="monotone"
                    dataKey="riskScore"
                    name="Risk Score"
                    stroke="#111111"
                    fill="url(#riskFillLight)"
                    strokeWidth={2}
                  />
                  <Area
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
              <span className="w-2.5 h-0.5 bg-[#111111]" /> Risk Score
            </span>
            <span className="flex items-center gap-1.5">
              <span className="w-2.5 h-0.5 bg-[#059669]" /> Latency (ms)
            </span>
          </div>
        </Panel>

        <Panel className="p-5">
          <div className="mb-4">
            <h2 className="text-sm font-semibold text-[#111111]">
              Findings Distribution by Category
            </h2>
            <p className="text-[11px] text-stone-500">
              Breakdown across Security, Bug, Performance, Architecture &amp; Test Coverage
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
            Computed from persisted PR review findings &amp; security scans
          </div>
        </Panel>
      </div>

      {/* Reproducible AI Review Quality Evaluation Suite */}
      <Panel className="overflow-hidden">
        <div className="px-5 py-4 border-b border-[#EFEFEA] bg-[#FAFAF8] flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold text-[#111111] flex items-center gap-2">
              <CheckCircle2 className="w-4 h-4 text-emerald-700" />
              Reproducible AI Review Quality Benchmark
            </h2>
            <p className="text-[11px] text-stone-500 mt-0.5 font-mono">
              {latestEval
                ? `Latest run: ${latestEval.promptVersion} · ${latestEval.modelUsed} · ${formatRelativeTime(latestEval.executedAt)}`
                : 'Execute benchmark to measure Precision, Recall, F1 & False-Positive Rate'}
            </p>
          </div>

          {latestEval && (
            <div className="flex flex-wrap items-center gap-2 text-xs font-mono tabular-nums">
              <Badge className="bg-emerald-50 text-emerald-800 border-emerald-200">
                Precision {(latestEval.precision * 100).toFixed(1)}%
              </Badge>
              <Badge className="bg-emerald-50 text-emerald-800 border-emerald-200">
                Recall {(latestEval.recall * 100).toFixed(1)}%
              </Badge>
              <Badge className="bg-[#111111] text-white border-[#111111]">
                F1 {(latestEval.f1Score * 100).toFixed(1)}%
              </Badge>
              <Badge className="bg-white text-stone-700 border-[#DCDCD5]">
                FPR {(latestEval.falsePositiveRate * 100).toFixed(1)}%
              </Badge>
              <Badge className="bg-white text-stone-700 border-[#DCDCD5]">
                {latestEval.avgLatencyMs}ms avg
              </Badge>
            </div>
          )}
        </div>

        {latestEval ? (
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
                {latestEval.caseResults.map((c) => (
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
