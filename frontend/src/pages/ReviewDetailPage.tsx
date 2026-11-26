import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { DiffEditor } from '@monaco-editor/react';
import {
  AlertTriangle,
  ArrowLeft,
  ArrowUpRight,
  Check,
  CheckCircle2,
  FlaskConical,
  GitBranch,
  Radar,
  Scale,
  Send,
  ShieldAlert,
  X,
} from 'lucide-react';
import { api } from '../lib/api';
import { Badge, Button, ErrorBanner, Panel, SkeletonBlock } from '../components/ui';
import {
  mergeDecisionClasses,
  predictionRiskClasses,
  severityClasses,
  statusClasses,
  toSafeGithubUrl,
} from '../lib/utils';

export function ReviewDetailPage() {
  const { reviewId = '' } = useParams();
  const [activeFileIdx, setActiveFileIdx] = useState(0);
  const [inlineDiff, setInlineDiff] = useState(true);
  const [banner, setBanner] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);
  const qc = useQueryClient();

  const detail = useQuery({
    queryKey: ['review', reviewId],
    queryFn: () => api.getReviewDetail(reviewId),
    enabled: !!reviewId,
    refetchInterval: (query) => {
      const status = query.state.data?.review.status;
      return status === 'QUEUED' || status === 'RUNNING' ? 900 : false;
    },
  });

  const prediction = useQuery({
    queryKey: ['prediction', reviewId],
    queryFn: () => api.getPrPrediction(reviewId),
    enabled: !!reviewId && detail.data?.review.status === 'COMPLETED',
    retry: false,
  });

  useEffect(() => {
    setActiveFileIdx(0);
  }, [reviewId]);

  const decideMutation = useMutation({
    mutationFn: ({ findingId, decision }: { findingId: string; decision: 'APPROVED' | 'DISMISSED' }) =>
      api.decideFinding(reviewId, findingId, decision),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['review', reviewId] });
      qc.invalidateQueries({ queryKey: ['audit'] });
    },
    onError: (err: unknown) => {
      setBanner({
        type: 'err',
        text: err instanceof Error ? err.message : 'Could not update finding status',
      });
    },
  });

  const publishMutation = useMutation({
    mutationFn: () => api.publishReview(reviewId),
    onSuccess: (updated) => {
      qc.invalidateQueries({ queryKey: ['review', reviewId] });
      qc.invalidateQueries({ queryKey: ['reviews'] });
      qc.invalidateQueries({ queryKey: ['audit'] });
      setBanner({
        type: 'ok',
        text: updated.githubCommentUrl
          ? `LIVE ON GITHUB! Approved review comments posted directly to GitHub PR #${updated.prNumber}.`
          : 'Approved findings recorded locally in workspace Audit Trail. (Add a workspace GitHub Token in Settings to post live comments on GitHub.)',
      });
    },
    onError: (err: unknown) => {
      setBanner({
        type: 'err',
        text: err instanceof Error ? err.message : 'Unable to publish comments',
      });
    },
  });

  if (detail.isLoading) {
    return (
      <div className="space-y-4">
        <SkeletonBlock className="h-24" />
        <SkeletonBlock className="h-[520px]" />
      </div>
    );
  }

  if (detail.isError || !detail.data) {
    return (
      <ErrorBanner
        message={detail.error instanceof Error ? detail.error.message : 'Review not found'}
        onRetry={() => detail.refetch()}
      />
    );
  }

  const { review, files, findings, changeImpacts, riskAssessment, testSelectionPlan } = detail.data;
  const activeFile = files[activeFileIdx] ?? files[0];
  const approvedCount = findings.filter((f) => f.status === 'APPROVED').length;
  const totalImpacted = changeImpacts.reduce((acc, c) => acc + c.totalImpacted, 0);
  const safePrUrl = !review.repositoryName.startsWith('acme-fintech/')
    ? toSafeGithubUrl(`https://github.com/${review.repositoryName}/pull/${review.prNumber}`)
    : null;
  const safeCommentUrl = toSafeGithubUrl(review.githubCommentUrl);

  return (
    <div className="space-y-5">
      {/* Top Navigation + Merge Decision Header */}
      <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4 pb-5 border-b border-[#E5E5E0]">
        <div className="space-y-1.5 min-w-0">
          <Link
            to="/reviews"
            className="inline-flex items-center gap-1.5 text-xs text-stone-500 hover:text-[#111111]"
          >
            <ArrowLeft className="w-3.5 h-3.5" /> Back to PR Reviews
          </Link>
          <div className="flex flex-wrap items-center gap-2.5">
            <span className="font-mono text-sm font-semibold text-[#111111]">
              #{review.prNumber}
            </span>
            <h1 className="text-lg sm:text-xl font-semibold tracking-[-0.025em] text-[#111111]">
              {review.title}
            </h1>
            <Badge className={mergeDecisionClasses(review.mergeDecision)}>
              {review.mergeDecision}
            </Badge>
            <Badge className={statusClasses(review.status)}>{review.status}</Badge>
          </div>
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] font-mono text-stone-500">
            <span>{review.repositoryName}</span>
            <span className="flex items-center gap-1">
              <GitBranch className="w-3 h-3" /> {review.sourceBranch} → {review.targetBranch}
            </span>
            <span>sha:{review.commitSha.slice(0, 8)}</span>
            <span>{review.modelUsed}</span>
            <span>{review.promptVersion}</span>
            <span>{review.processingMs}ms</span>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2.5 shrink-0">
          {safePrUrl && (
            <a
              href={safePrUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="h-9 px-3 rounded-md bg-white hover:bg-stone-50 border border-[#DCDCD5] text-xs font-medium text-[#111111] inline-flex items-center gap-1 transition-colors"
            >
              Open PR on GitHub <ArrowUpRight className="w-3.5 h-3.5" />
            </a>
          )}

          <div className="px-3.5 py-2 rounded-lg bg-white border border-[#E5E5E0] text-right">
            <div className="text-[10px] font-mono uppercase text-stone-500">Risk Score</div>
            <div
              className={`text-base font-mono font-semibold tabular-nums ${
                review.riskScore >= 65
                  ? 'text-red-700'
                  : review.riskScore > 25
                  ? 'text-amber-700'
                  : 'text-emerald-700'
              }`}
            >
              {review.riskScore}/100
            </div>
          </div>

          {safeCommentUrl ? (
            <a
              href={safeCommentUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="h-9 px-3.5 rounded-md bg-emerald-700 hover:bg-emerald-800 text-white text-xs font-medium inline-flex items-center gap-1.5 transition-colors"
            >
              <CheckCircle2 className="w-3.5 h-3.5" />
              View Live Comment on GitHub
              <ArrowUpRight className="w-3.5 h-3.5" />
            </a>
          ) : (
            <Button
              onClick={() => publishMutation.mutate()}
              disabled={publishMutation.isPending || review.publishedToGithub}
            >
              {review.publishStatus === 'PUBLISHED_TO_GITHUB' ? (
                <>
                  <CheckCircle2 className="w-3.5 h-3.5" />
                  Published to GitHub
                </>
              ) : review.publishedToGithub ? (
                <>
                  <CheckCircle2 className="w-3.5 h-3.5" />
                  Approved Locally
                </>
              ) : (
                <>
                  <Send className="w-3.5 h-3.5" />
                  Publish Approved ({approvedCount})
                </>
              )}
            </Button>
          )}
        </div>
      </div>

      {banner && (
        <div
          role={banner.type === 'err' ? 'alert' : 'status'}
          className={`rounded-lg px-4 py-2.5 text-xs flex items-center justify-between border ${
            banner.type === 'ok'
              ? 'bg-emerald-50 border-emerald-200 text-emerald-900'
              : 'bg-red-50 border-red-200 text-red-900'
          }`}
        >
          <span>{banner.text}</span>
          <button onClick={() => setBanner(null)} aria-label="Dismiss message" className="cursor-pointer">
            <X className="w-3.5 h-3.5" />
          </button>
        </div>
      )}

      {/* Deterministic Change Risk Intelligence + AST Impact + Recommended Tests */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <Panel className="lg:col-span-2 p-4 space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="text-[10px] font-mono uppercase tracking-[0.12em] text-stone-500 flex items-center gap-1.5">
              <Scale className="w-3.5 h-3.5 text-[#111111]" />
              Deterministic Change Risk Assessment {riskAssessment ? `(Policy v${riskAssessment.policyVersion})` : ''}
            </div>
            {riskAssessment && (
              <div className="flex flex-wrap items-center gap-1.5">
                {riskAssessment.breakingApiCount > 0 && (
                  <Badge className={severityClasses('CRITICAL')}>
                    {riskAssessment.breakingApiCount} Breaking API
                  </Badge>
                )}
                {riskAssessment.migrationRiskCount > 0 && (
                  <Badge className={severityClasses('CRITICAL')}>
                    {riskAssessment.migrationRiskCount} DB Migration Risk
                  </Badge>
                )}
                {riskAssessment.criticalSecurityCount > 0 && (
                  <Badge className={severityClasses('CRITICAL')}>
                    {riskAssessment.criticalSecurityCount} Critical Security
                  </Badge>
                )}
              </div>
            )}
          </div>
          <p className="text-xs sm:text-[13px] text-stone-800 leading-relaxed font-medium">
            {riskAssessment?.decisionSummary || review.summary || 'Pipeline is currently analyzing this pull request...'}
          </p>
          {riskAssessment && riskAssessment.triggeredPolicies.length > 0 && (
            <div className="flex flex-wrap items-center gap-1.5">
              <span className="text-[10px] font-mono uppercase text-stone-500">Triggered Policies:</span>
              {riskAssessment.triggeredPolicies.map((pol) => (
                <span
                  key={pol}
                  className="text-[10px] font-mono px-2 py-0.5 rounded bg-[#111111] text-white"
                >
                  {pol}
                </span>
              ))}
            </div>
          )}
          {riskAssessment?.safetyDisclaimer && (
            <div className="text-[11px] text-stone-500 border-t border-[#EFEFEA] pt-2">
              {riskAssessment.safetyDisclaimer}
            </div>
          )}
        </Panel>

        <Panel className="p-4 space-y-2.5">
          <div className="text-[10px] font-mono uppercase tracking-[0.12em] text-stone-500 flex items-center gap-1.5">
            <Radar className="w-3.5 h-3.5 text-amber-700" />
            AST Change Impact Blast Radius
          </div>
          <div className="text-xs text-stone-700">
            <span className="font-mono font-semibold text-[#111111]">{totalImpacted}</span>{' '}
            downstream dependents across <span className="font-mono">{files.length}</span> modified
            file(s).
          </div>
          <div className="flex flex-wrap gap-1.5">
            {changeImpacts
              .flatMap((c) => c.impactedNodes)
              .slice(0, 6)
              .map((n) => (
                <Link
                  key={n.nodeName}
                  to={`/code-intel?repo=${encodeURIComponent(review.repositoryId)}&node=${encodeURIComponent(n.nodeName)}`}
                  title={n.impactReason}
                  className="text-[10px] font-mono px-2 py-0.5 rounded bg-amber-50 hover:bg-amber-100 text-amber-900 border border-amber-200 transition-colors"
                >
                  {n.nodeName} (hop {n.hopDistance})
                </Link>
              ))}
          </div>
        </Panel>
      </div>

      {/* Predictive Engineering Failure Forecast (Advisory, Non-Blocking) */}
      {prediction.isLoading && detail.data?.review.status === 'COMPLETED' && (
        <SkeletonBlock className="h-32" />
      )}
      {prediction.data && (
        <Panel className="p-4 space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <div className="text-[10px] font-mono uppercase tracking-[0.12em] text-stone-500">
                Predictive Failure Forecast · {prediction.data.engineVersion}
              </div>
              <div className="text-xs text-stone-700 mt-0.5">
                Overall Engineering Risk:{' '}
                <span className="font-mono font-semibold text-[#111111]">
                  {prediction.data.overallEngineeringRiskScore}/100 ({prediction.data.overallRiskLevel})
                </span>{' '}
                · Historical samples:{' '}
                <span className="font-mono">{prediction.data.historicalSampleSize}</span>
              </div>
            </div>
            <Link
              to={`/predictions?reviewId=${encodeURIComponent(reviewId)}`}
              className="h-8 px-3 rounded-md bg-[#111111] text-white text-xs font-medium inline-flex items-center gap-1 hover:bg-stone-800 transition-colors"
            >
              Inspect Full Predictive Evidence <ArrowUpRight className="w-3.5 h-3.5" />
            </Link>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-2.5">
            {prediction.data.predictions.map((item) => (
              <div key={item.id} className="rounded-md border border-[#E5E5E0] bg-[#FAFAF8] p-2.5 space-y-1">
                <div className="text-[10px] font-mono uppercase text-stone-500 truncate">
                  {item.category.replace(/_/g, ' ')}
                </div>
                <div className="flex items-center justify-between gap-1">
                  <Badge className={predictionRiskClasses(item.riskLevel)}>
                    {item.riskLevel} ({item.riskScore})
                  </Badge>
                  <span className="text-[10px] font-mono text-stone-500">{item.confidenceLevel}</span>
                </div>
                <div className="text-[11px] text-stone-600 line-clamp-2">{item.explanation}</div>
              </div>
            ))}
          </div>
          <div className="text-[11px] text-stone-500 border-t border-[#EFEFEA] pt-2">
            {prediction.data.policyBoundaryNote}
          </div>
        </Panel>
      )}

      {/* Explainable Risk Factors + Intelligent Test Selection */}
      {(riskAssessment?.factors?.length || testSelectionPlan) && (
        <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
          {riskAssessment && riskAssessment.factors.length > 0 && (
            <Panel className="p-4 space-y-3">
              <div className="flex items-center justify-between">
                <h2 className="text-xs font-mono font-semibold uppercase tracking-[0.08em] text-[#111111] flex items-center gap-1.5">
                  <AlertTriangle className="w-3.5 h-3.5 text-stone-600" />
                  Explainable Risk Factors ({riskAssessment.factors.length})
                </h2>
                <span className="text-[11px] font-mono text-stone-500">
                  Total Score: {riskAssessment.riskScore}/100
                </span>
              </div>
              <div className="space-y-2 max-h-56 overflow-y-auto pr-1">
                {riskAssessment.factors.map((factor, idx) => (
                  <div
                    key={idx}
                    className="p-2.5 rounded-lg bg-[#FAFAF8] border border-[#EAEAE4] text-xs space-y-1"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-1.5 min-w-0">
                        <Badge className={severityClasses(factor.severity)}>{factor.severity}</Badge>
                        <span className="font-semibold text-[#111111] truncate">{factor.title}</span>
                      </div>
                      <span className="font-mono text-[11px] font-semibold text-[#111111] shrink-0">
                        +{factor.riskPoints} pts
                      </span>
                    </div>
                    <div className="text-[10.5px] font-mono text-stone-500 flex flex-wrap gap-x-2">
                      <span>{factor.category}</span>
                      <span>·</span>
                      <span>{factor.policyRule}</span>
                      <span>·</span>
                      <span>conf {(factor.confidence * 100).toFixed(0)}%</span>
                    </div>
                    <div className="text-[11px] text-stone-600">{factor.evidence}</div>
                  </div>
                ))}
              </div>
            </Panel>
          )}

          {testSelectionPlan && (
            <Panel className="p-4 space-y-3">
              <div className="flex items-center justify-between gap-2">
                <h2 className="text-xs font-mono font-semibold uppercase tracking-[0.08em] text-[#111111] flex items-center gap-1.5">
                  <FlaskConical className="w-3.5 h-3.5 text-stone-600" />
                  Recommended Tests ({testSelectionPlan.recommendedCount})
                </h2>
                <Badge className="bg-[#111111] text-white border-[#111111]">
                  {testSelectionPlan.executionStatus}
                </Badge>
              </div>
              <p className="text-[11px] text-stone-500 leading-relaxed">{testSelectionPlan.disclaimer}</p>
              <div className="space-y-2 max-h-48 overflow-y-auto pr-1">
                {testSelectionPlan.selectedTests.map((t, idx) => (
                  <div
                    key={idx}
                    className="p-2.5 rounded-lg bg-[#FAFAF8] border border-[#EAEAE4] text-xs space-y-1"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-mono font-semibold text-[#111111] truncate">{t.testName}</span>
                      <Badge className={severityClasses(t.priority)}>{t.priority}</Badge>
                    </div>
                    <div className="text-[11px] text-stone-600">{t.selectionReason}</div>
                    <code className="block text-[10.5px] font-mono px-2 py-1 rounded bg-[#141416] text-stone-200 overflow-x-auto">
                      {t.suggestedCommand}
                    </code>
                  </div>
                ))}
              </div>
            </Panel>
          )}
        </div>
      )}

      {/* Main Workbench: Monaco Diff Editor + Crisp Findings Drawer */}
      <div className="grid grid-cols-1 xl:grid-cols-12 gap-5">
        <Panel className="xl:col-span-7 overflow-hidden flex flex-col">
          <div className="px-4 py-2.5 border-b border-[#EFEFEA] bg-[#FAFAF8] flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-1.5 overflow-x-auto">
              {files.map((f, idx) => (
                <button
                  key={f.filePath}
                  onClick={() => setActiveFileIdx(idx)}
                  className={`px-2.5 py-1 rounded-md text-xs font-mono transition-colors cursor-pointer ${
                    idx === activeFileIdx
                      ? 'bg-[#111111] text-white'
                      : 'text-stone-600 hover:bg-stone-200/70'
                  }`}
                >
                  {f.filePath.split('/').pop()}{' '}
                  <span className={idx === activeFileIdx ? 'text-emerald-300' : 'text-emerald-700'}>
                    +{f.addedCount}
                  </span>{' '}
                  <span className={idx === activeFileIdx ? 'text-red-300' : 'text-red-600'}>
                    -{f.removedCount}
                  </span>
                </button>
              ))}
            </div>
            <button
              onClick={() => setInlineDiff((v) => !v)}
              className="text-[11px] font-mono text-stone-700 hover:text-[#111111] px-2.5 py-1 rounded-md bg-white border border-[#DCDCD5] cursor-pointer"
            >
              {inlineDiff ? 'Split View' : 'Inline View'}
            </button>
          </div>

          <div className="h-[580px] bg-[#141416]">
            {activeFile ? (
              <DiffEditor
                height="100%"
                language={activeFile.language}
                original={activeFile.originalContent}
                modified={activeFile.modifiedContent}
                theme="vs-dark"
                options={{
                  readOnly: true,
                  renderSideBySide: !inlineDiff,
                  wordWrap: 'on',
                  minimap: { enabled: false },
                  fontSize: 12.5,
                  fontFamily: 'JetBrains Mono',
                  scrollBeyondLastLine: false,
                }}
              />
            ) : (
              <div className="p-8 text-xs text-stone-400">No diff hunks available.</div>
            )}
          </div>
        </Panel>

        <div className="xl:col-span-5 space-y-3 max-h-[630px] overflow-y-auto pr-1">
          <div className="flex items-center justify-between px-1">
            <h2 className="text-xs font-semibold uppercase tracking-[0.08em] text-stone-500 font-mono">
              Review Findings ({findings.length})
            </h2>
            <span className="text-[11px] text-stone-500">
              Human approval required before GitHub publish
            </span>
          </div>

          {findings.length === 0 ? (
            <Panel className="p-6 text-center space-y-2">
              <CheckCircle2 className="w-7 h-7 text-emerald-700 mx-auto" />
              <div className="text-sm font-semibold text-[#111111]">No configured blocking issues detected</div>
              <p className="text-xs text-stone-600">
                No security, contract, migration, or static issues triggered for this diff. Execute the recommended tests before merging.
              </p>
            </Panel>
          ) : (
            findings.map((f) => (
              <Panel key={f.id} className="p-4 space-y-3">
                <div className="flex items-start justify-between gap-2">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <Badge className={severityClasses(f.severity)}>{f.severity}</Badge>
                    <Badge className="bg-stone-100 text-stone-700 border-stone-200 font-mono">
                      {f.category}
                    </Badge>
                    <span className="text-[10px] font-mono text-stone-500">{f.ruleId}</span>
                  </div>
                  <Badge className={statusClasses(f.status)}>{f.status}</Badge>
                </div>

                <div>
                  <h3 className="text-[13px] font-semibold text-[#111111] flex items-center gap-1.5">
                    <ShieldAlert className="w-3.5 h-3.5 text-stone-500 shrink-0" />
                    {f.title}
                  </h3>
                  <div className="text-[11px] font-mono text-stone-500 mt-1">
                    {f.filePath}:{f.lineNumber} · confidence{' '}
                    {(Number(f.confidence) * 100).toFixed(0)}%
                  </div>
                  <p className="text-xs text-stone-600 mt-2 leading-relaxed">{f.explanation}</p>
                </div>

                {(f.evidence || f.suggestedPatch) && (
                  <div className="rounded-lg border border-[#E5E5E0] overflow-hidden font-mono text-[11px] divide-y divide-[#EFEFEA]">
                    {f.evidence && (
                      <div className="px-3 py-2 bg-red-50/75 text-red-900 flex items-start gap-2 overflow-x-auto">
                        <span className="text-red-500 font-bold select-none">-</span>
                        <code className="break-all">{f.evidence}</code>
                      </div>
                    )}
                    {f.suggestedPatch && (
                      <div className="px-3 py-2 bg-emerald-50/75 text-emerald-950 flex items-start gap-2 overflow-x-auto">
                        <span className="text-emerald-600 font-bold select-none">+</span>
                        <pre className="whitespace-pre-wrap">{f.suggestedPatch}</pre>
                      </div>
                    )}
                  </div>
                )}

                <div className="pt-2 border-t border-[#EFEFEA] flex items-center justify-between gap-2">
                  <span className="text-[10px] font-mono text-stone-500">
                    {f.reviewedBy ? `Reviewed by ${f.reviewedBy}` : 'Awaiting reviewer decision'}
                  </span>
                  <div className="flex items-center gap-1.5">
                    <Button
                      size="sm"
                      variant={f.status === 'APPROVED' ? 'primary' : 'secondary'}
                      onClick={() => decideMutation.mutate({ findingId: f.id, decision: 'APPROVED' })}
                      disabled={decideMutation.isPending}
                    >
                      <Check className="w-3 h-3" />
                      Approve
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() =>
                        decideMutation.mutate({ findingId: f.id, decision: 'DISMISSED' })
                      }
                      disabled={decideMutation.isPending}
                    >
                      <X className="w-3 h-3" />
                      Dismiss
                    </Button>
                  </div>
                </div>
              </Panel>
            ))
          )}
        </div>
      </div>
    </div>
  );
}
