import { useEffect, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import {
  Activity,
  AlertTriangle,
  ArrowUpRight,
  Database,
  FlaskConical,
  Gauge,
  History,
  Info,
  Layers,
  Network,
  Plus,
  RefreshCw,
  ShieldAlert,
  Sparkles,
} from 'lucide-react';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from '../lib/api';
import { useForgeStore } from '../lib/store';
import { LiveProcessingPanel } from '../components/LiveProcessingPanel';
import { Badge, Button, ErrorBanner, Panel, SkeletonBlock } from '../components/ui';
import { formatRelativeTime, predictionRiskClasses, statusClasses } from '../lib/utils';

type BreakdownTab = 'FILE' | 'CLASS' | 'METHOD' | 'MODULE' | 'SERVICE';
type IngestKind = 'test' | 'deployment' | 'incident' | 'defect';

const CATEGORY_LABELS: Record<string, string> = {
  BUG_RISK: 'Bug / Defect Risk',
  TEST_FAILURE_RISK: 'Test Failure Risk',
  PRODUCTION_INCIDENT_RISK: 'Production Incident Risk',
  CHANGE_FAILURE_RISK: 'Change Failure / Rollback Risk',
  PERFORMANCE_REGRESSION: 'Static Performance Warnings',
};

export function PredictiveIntelligencePage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const qc = useQueryClient();
  const user = useForgeStore((s) => s.user);
  const canMutate = user?.role === 'OWNER' || user?.role === 'ADMIN' || user?.role === 'REVIEWER';

  const [selectedReviewId, setSelectedReviewId] = useState<string>(searchParams.get('reviewId') || '');
  const [breakdownTab, setBreakdownTab] = useState<BreakdownTab>('FILE');
  const [showIngest, setShowIngest] = useState(false);
  const [ingestKind, setIngestKind] = useState<IngestKind>('test');
  const [banner, setBanner] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);

  // Historical outcome ingestion form state
  const [formRepoId, setFormRepoId] = useState('');
  const [formPrNum, setFormPrNum] = useState('');
  const [formTitleOrName, setFormTitleOrName] = useState('');
  const [formServiceOrPath, setFormServiceOrPath] = useState('');
  const [formModule, setFormModule] = useState('');
  const [formOutcomeOrSev, setFormOutcomeOrSev] = useState('FAILED');
  const [formChangedConfig, setFormChangedConfig] = useState(false);
  const [formNote, setFormNote] = useState('');

  const overview = useQuery({
    queryKey: ['prediction-overview'],
    queryFn: api.getPredictionOverview,
  });

  const reviews = useQuery({
    queryKey: ['reviews'],
    queryFn: () => api.listReviews(),
  });

  const repos = useQuery({
    queryKey: ['repos'],
    queryFn: api.listRepos,
  });

  useEffect(() => {
    if (!formRepoId && repos.data && repos.data.length > 0) {
      setFormRepoId(repos.data[0].id);
    }
  }, [repos.data, formRepoId]);

  useEffect(() => {
    const paramId = searchParams.get('reviewId');
    if (paramId && paramId !== selectedReviewId) {
      setSelectedReviewId(paramId);
      return;
    }
    if (!selectedReviewId) {
      const firstFromOverview = overview.data?.recentPredictions[0]?.reviewId;
      const firstFromReviews = reviews.data?.find((r) => r.status === 'COMPLETED')?.id || reviews.data?.[0]?.id;
      const initial = firstFromOverview || firstFromReviews || '';
      if (initial) {
        setSelectedReviewId(initial);
      }
    }
  }, [searchParams, selectedReviewId, overview.data, reviews.data]);

  const prPrediction = useQuery({
    queryKey: ['prediction', selectedReviewId],
    queryFn: () => api.getPrPrediction(selectedReviewId),
    enabled: !!selectedReviewId,
  });

  const prEvidence = useQuery({
    queryKey: ['prediction-evidence', selectedReviewId],
    queryFn: () => api.getPrPredictionEvidence(selectedReviewId),
    enabled: !!selectedReviewId,
  });

  const prTests = useQuery({
    queryKey: ['prediction-tests', selectedReviewId],
    queryFn: () => api.getPrPredictionTests(selectedReviewId),
    enabled: !!selectedReviewId,
  });

  const prHistory = useQuery({
    queryKey: ['prediction-history', selectedReviewId],
    queryFn: () => api.getPrPredictionHistory(selectedReviewId),
    enabled: !!selectedReviewId,
  });

  const prRuns = useQuery({
    queryKey: ['prediction-runs', selectedReviewId],
    queryFn: () => api.listPrPredictionRuns(selectedReviewId),
    enabled: !!selectedReviewId,
  });

  const recomputeMutation = useMutation({
    mutationFn: (reviewId: string) => api.runPrPrediction(reviewId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['prediction-overview'] });
      qc.invalidateQueries({ queryKey: ['prediction', selectedReviewId] });
      qc.invalidateQueries({ queryKey: ['prediction-evidence', selectedReviewId] });
      qc.invalidateQueries({ queryKey: ['prediction-tests', selectedReviewId] });
      qc.invalidateQueries({ queryKey: ['prediction-history', selectedReviewId] });
      qc.invalidateQueries({ queryKey: ['prediction-runs', selectedReviewId] });
      setBanner({ type: 'ok', text: 'Recomputed deterministic predictive risk baseline with latest historical evidence.' });
    },
    onError: (err: unknown) => {
      setBanner({ type: 'err', text: err instanceof Error ? err.message : 'Unable to recompute prediction' });
    },
  });

  const evalMutation = useMutation({
    mutationFn: () => api.runPredictionEvaluation(),
    onSuccess: (runs) => {
      qc.invalidateQueries({ queryKey: ['prediction-overview'] });
      const evaluatedCount = runs.filter((r) => r.status === 'EVALUATED').length;
      setBanner({
        type: 'ok',
        text:
          evaluatedCount > 0
            ? `Chronological held-out evaluation completed across ${evaluatedCount} category/categories with sufficient confirmed outcomes.`
            : 'Chronological evaluation checked confirmed outcomes: insufficient labelled history to report accuracy metrics yet.',
      });
    },
    onError: (err: unknown) => {
      setBanner({ type: 'err', text: err instanceof Error ? err.message : 'Unable to run held-out evaluation' });
    },
  });

  const ingestMutation = useMutation({
    mutationFn: async () => {
      const prNumber = formPrNum.trim() ? Number(formPrNum.trim()) : undefined;
      if (ingestKind === 'test') {
        return api.ingestTestExecution({
          repositoryId: formRepoId,
          prNumber,
          testName: formTitleOrName.trim(),
          testFilePath: formServiceOrPath.trim() || undefined,
          moduleName: formModule.trim() || undefined,
          outcome: formOutcomeOrSev as 'PASSED' | 'FAILED' | 'SKIPPED' | 'FLAKY',
          errorSummary: formNote.trim() || undefined,
        });
      }
      if (ingestKind === 'deployment') {
        return api.ingestDeployment({
          repositoryId: formRepoId,
          serviceName: formTitleOrName.trim(),
          environment: formServiceOrPath.trim() || 'production',
          prNumber,
          outcome: formOutcomeOrSev as 'SUCCEEDED' | 'FAILED' | 'ROLLED_BACK',
          changedConfig: formChangedConfig,
          summary: formNote.trim() || undefined,
        });
      }
      if (ingestKind === 'incident') {
        return api.ingestIncident({
          repositoryId: formRepoId,
          title: formTitleOrName.trim(),
          serviceName: formServiceOrPath.trim() || 'core-service',
          severity: formOutcomeOrSev as 'SEV1' | 'SEV2' | 'SEV3' | 'SEV4',
          affectedModules: formModule.trim() ? formModule.split(',').map((m) => m.trim()).filter(Boolean) : [],
          relatedPrNumber: prNumber,
          evidenceNote: formNote.trim() || undefined,
        });
      }
      return api.ingestDefect({
        repositoryId: formRepoId,
        title: formTitleOrName.trim(),
        severity: formOutcomeOrSev as 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW',
        moduleName: formModule.trim() || undefined,
        filePaths: formServiceOrPath.trim() ? formServiceOrPath.split(',').map((p) => p.trim()).filter(Boolean) : [],
        introducedPrNumber: prNumber,
      });
    },
    onSuccess: (res) => {
      qc.invalidateQueries({ queryKey: ['prediction-overview'] });
      if (selectedReviewId) {
        qc.invalidateQueries({ queryKey: ['prediction-history', selectedReviewId] });
        qc.invalidateQueries({ queryKey: ['prediction-tests', selectedReviewId] });
      }
      setFormTitleOrName('');
      setFormNote('');
      setBanner({
        type: 'ok',
        text: res.duplicate
          ? `Idempotent skip: ${res.recordType} already recorded (${res.externalEventKey}).`
          : `Recorded real ${res.recordType} outcome. Click Recompute Forecast to incorporate it into a PR prediction.`,
      });
    },
    onError: (err: unknown) => {
      setBanner({ type: 'err', text: err instanceof Error ? err.message : 'Unable to ingest historical record' });
    },
  });

  const handleSelectReview = (id: string) => {
    setSelectedReviewId(id);
    if (id) {
      setSearchParams({ reviewId: id });
    }
  };

  const handleSwitchIngestKind = (next: IngestKind) => {
    setIngestKind(next);
    if (next === 'test') setFormOutcomeOrSev('FAILED');
    else if (next === 'deployment') setFormOutcomeOrSev('ROLLED_BACK');
    else if (next === 'incident') setFormOutcomeOrSev('SEV2');
    else setFormOutcomeOrSev('HIGH');
  };

  const handleIngestSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!formRepoId) {
      setBanner({ type: 'err', text: 'Select a repository first.' });
      return;
    }
    ingestMutation.mutate();
  };

  const ov = overview.data;
  const pred = prPrediction.data;
  const evData = prEvidence.data;
  const testsData = prTests.data;
  const histData = prHistory.data;

  const activeScopeRows =
    breakdownTab === 'FILE'
      ? evData?.byFile ?? []
      : breakdownTab === 'CLASS'
        ? evData?.byClass ?? []
        : breakdownTab === 'METHOD'
          ? evData?.byMethod ?? []
          : breakdownTab === 'MODULE'
            ? evData?.byModule ?? []
            : evData?.byService ?? [];

  if (overview.isLoading) {
    return (
      <div className="space-y-5">
        <SkeletonBlock className="h-16" />
        <div className="grid grid-cols-2 lg:grid-cols-5 gap-3">
          {Array.from({ length: 5 }).map((_, i) => (
            <SkeletonBlock key={i} className="h-24" />
          ))}
        </div>
        <SkeletonBlock className="h-80" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex flex-col md:flex-row md:items-end justify-between gap-4 pb-5 border-b border-[#E5E5E0]">
        <div>
          <div className="text-[10.5px] font-mono uppercase tracking-[0.14em] text-stone-500">
            Predictive Engineering Intelligence · Deterministic Baselines
          </div>
          <h1 className="text-2xl font-semibold tracking-[-0.025em] text-[#111111] mt-1">
            Predict What Could Fail Before Production
          </h1>
          <p className="text-xs text-stone-600 mt-1 max-w-3xl">
            Explainable defect, test failure, production incident, deployment/rollback, and static performance risk
            forecasts grounded in real diff, AST dependency, and historical engineering evidence.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {canMutate && (
            <Button variant="secondary" size="sm" onClick={() => setShowIngest((v) => !v)}>
              <Plus className="w-3.5 h-3.5" />
              Record Historical Outcome
            </Button>
          )}
          {canMutate && (
            <Button
              size="sm"
              onClick={() => evalMutation.mutate()}
              disabled={evalMutation.isPending}
            >
              <Sparkles className="w-3.5 h-3.5" />
              {evalMutation.isPending ? 'Evaluating...' : 'Run Held-Out Evaluation'}
            </Button>
          )}
        </div>
      </div>

      {overview.isError && <ErrorBanner message="Failed to load predictive engineering overview." />}

      {banner && (
        <div
          role={banner.type === 'err' ? 'alert' : 'status'}
          className={`rounded-lg px-4 py-2.5 text-xs border flex items-center justify-between ${
            banner.type === 'ok'
              ? 'bg-emerald-50 border-emerald-200 text-emerald-900'
              : 'bg-red-50 border-red-200 text-red-900'
          }`}
        >
          <span>{banner.text}</span>
          <button onClick={() => setBanner(null)} className="text-xs font-mono underline cursor-pointer">
            Dismiss
          </button>
        </div>
      )}

      {/* Real-Time Predictive Pipeline & Evaluation Execution Timelines */}
      {selectedReviewId && (
        <LiveProcessingPanel
          resourceId={selectedReviewId}
          eventTypes={['PR_PREDICTION']}
          collapseWhenCompleted
          onRetry={canMutate ? () => recomputeMutation.mutate(selectedReviewId) : undefined}
        />
      )}
      <LiveProcessingPanel
        eventTypes={['PREDICTION_EVALUATION']}
        collapseWhenCompleted
        autoRecover={false}
        onRetry={canMutate ? () => evalMutation.mutate() : undefined}
      />

      {/* Honest Historical Data Availability Banner */}
      {ov && (
        <div
          className={`rounded-lg border px-4 py-3 text-xs flex items-start gap-2.5 ${
            ov.limitedHistoricalData
              ? 'bg-amber-50/70 border-amber-200 text-amber-950'
              : 'bg-white border-[#E5E5E0] text-stone-700'
          }`}
        >
          <Info className="w-4 h-4 shrink-0 mt-0.5" />
          <div>
            <span className="font-mono font-semibold uppercase text-[10.5px] mr-2">
              {ov.limitedHistoricalData ? 'Limited Historical Evidence' : 'Historical Evidence Grounding'}
            </span>
            <span>{ov.historicalDataNotice}</span>
          </div>
        </div>
      )}

      {/* Historical Ingestion Form */}
      {showIngest && canMutate && (
        <Panel className="p-4 space-y-4 border-[#111111]">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <h2 className="text-xs font-mono font-semibold uppercase tracking-[0.08em] text-[#111111]">
                Ingest Confirmed Historical Outcome (Idempotent)
              </h2>
              <p className="text-[11px] text-stone-500 mt-0.5">
                Record real CI test executions, deployments/rollbacks, confirmed incidents, or confirmed defects.
              </p>
            </div>
            <div className="flex items-center gap-1 bg-[#FAFAF8] p-1 rounded-md border border-[#E5E5E0]">
              {(['test', 'deployment', 'incident', 'defect'] as IngestKind[]).map((k) => (
                <button
                  key={k}
                  type="button"
                  onClick={() => handleSwitchIngestKind(k)}
                  className={`px-2.5 py-1 rounded text-[11px] font-mono uppercase cursor-pointer ${
                    ingestKind === k ? 'bg-[#111111] text-white' : 'text-stone-600 hover:text-[#111111]'
                  }`}
                >
                  {k}
                </button>
              ))}
            </div>
          </div>

          <form onSubmit={handleIngestSubmit} className="grid grid-cols-1 md:grid-cols-3 gap-3">
            <div>
              <label className="block text-[10.5px] font-mono uppercase text-stone-500 mb-1">Repository</label>
              <select
                value={formRepoId}
                onChange={(e) => setFormRepoId(e.target.value)}
                className="w-full h-9 px-2.5 rounded-md border border-[#DCDCD5] bg-white text-xs"
                required
              >
                {(repos.data ?? []).map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.fullName}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-[10.5px] font-mono uppercase text-stone-500 mb-1">
                {ingestKind === 'test'
                  ? 'Test Name'
                  : ingestKind === 'deployment'
                    ? 'Service Name'
                    : 'Title'}
              </label>
              <input
                value={formTitleOrName}
                onChange={(e) => setFormTitleOrName(e.target.value)}
                placeholder={
                  ingestKind === 'test'
                    ? 'PaymentSettlementServiceTest'
                    : ingestKind === 'deployment'
                      ? 'payments-api'
                      : 'Settlement retry timeout incident'
                }
                className="w-full h-9 px-2.5 rounded-md border border-[#DCDCD5] bg-white text-xs"
                required
              />
            </div>
            <div>
              <label className="block text-[10.5px] font-mono uppercase text-stone-500 mb-1">
                {ingestKind === 'test' || ingestKind === 'deployment' ? 'Outcome' : 'Severity'}
              </label>
              <select
                value={formOutcomeOrSev}
                onChange={(e) => setFormOutcomeOrSev(e.target.value)}
                className="w-full h-9 px-2.5 rounded-md border border-[#DCDCD5] bg-white text-xs font-mono"
              >
                {ingestKind === 'test' &&
                  ['PASSED', 'FAILED', 'FLAKY', 'SKIPPED'].map((o) => (
                    <option key={o} value={o}>
                      {o}
                    </option>
                  ))}
                {ingestKind === 'deployment' &&
                  ['SUCCEEDED', 'FAILED', 'ROLLED_BACK'].map((o) => (
                    <option key={o} value={o}>
                      {o}
                    </option>
                  ))}
                {ingestKind === 'incident' &&
                  ['SEV1', 'SEV2', 'SEV3', 'SEV4'].map((o) => (
                    <option key={o} value={o}>
                      {o}
                    </option>
                  ))}
                {ingestKind === 'defect' &&
                  ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].map((o) => (
                    <option key={o} value={o}>
                      {o}
                    </option>
                  ))}
              </select>
            </div>
            <div>
              <label className="block text-[10.5px] font-mono uppercase text-stone-500 mb-1">
                {ingestKind === 'test'
                  ? 'Test File Path (optional)'
                  : ingestKind === 'deployment'
                    ? 'Environment (e.g. production)'
                    : ingestKind === 'defect'
                      ? 'File Paths (comma-separated)'
                      : 'Service Name'}
              </label>
              <input
                value={formServiceOrPath}
                onChange={(e) => setFormServiceOrPath(e.target.value)}
                placeholder={
                  ingestKind === 'test'
                    ? 'src/test/java/PaymentSettlementServiceTest.java'
                    : ingestKind === 'deployment'
                      ? 'production'
                      : ingestKind === 'defect'
                        ? 'src/main/java/PaymentSettlementService.java'
                        : 'payments-api'
                }
                className="w-full h-9 px-2.5 rounded-md border border-[#DCDCD5] bg-white text-xs"
              />
            </div>
            <div>
              <label className="block text-[10.5px] font-mono uppercase text-stone-500 mb-1">
                {ingestKind === 'deployment' ? 'Config Change & Related PR #' : 'Module(s) & Related PR # (optional)'}
              </label>
              <div className="flex items-center gap-2">
                {ingestKind === 'deployment' ? (
                  <label className="flex-1 h-9 px-2.5 rounded-md border border-[#DCDCD5] bg-white text-xs flex items-center gap-2 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={formChangedConfig}
                      onChange={(e) => setFormChangedConfig(e.target.checked)}
                    />
                    <span>Changed config/infra</span>
                  </label>
                ) : (
                  <input
                    value={formModule}
                    onChange={(e) => setFormModule(e.target.value)}
                    placeholder="payments"
                    className="flex-1 h-9 px-2.5 rounded-md border border-[#DCDCD5] bg-white text-xs"
                  />
                )}
                <input
                  value={formPrNum}
                  onChange={(e) => setFormPrNum(e.target.value)}
                  placeholder="PR #"
                  type="number"
                  className="w-20 h-9 px-2 rounded-md border border-[#DCDCD5] bg-white text-xs font-mono"
                />
              </div>
            </div>
            <div>
              <label className="block text-[10.5px] font-mono uppercase text-stone-500 mb-1">
                Summary / Evidence Note
              </label>
              <div className="flex gap-2">
                <input
                  value={formNote}
                  onChange={(e) => setFormNote(e.target.value)}
                  placeholder="Verified in CI run or postmortem"
                  className="flex-1 h-9 px-2.5 rounded-md border border-[#DCDCD5] bg-white text-xs"
                />
                <Button type="submit" size="sm" disabled={ingestMutation.isPending}>
                  {ingestMutation.isPending ? 'Saving...' : 'Save'}
                </Button>
              </div>
            </div>
          </form>
        </Panel>
      )}

      {/* Workspace KPI Strip */}
      {ov && (
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3">
          <Panel className="p-3.5">
            <div className="text-[10px] font-mono uppercase text-stone-500">PR Forecasts</div>
            <div className="text-2xl font-mono font-semibold text-[#111111] mt-1">{ov.totalPredictions}</div>
            <div className="text-[11px] text-stone-500 mt-0.5">{ov.highRiskPredictions} High / Critical</div>
          </Panel>
          <Panel className="p-3.5">
            <div className="text-[10px] font-mono uppercase text-stone-500">Test Executions</div>
            <div className="text-2xl font-mono font-semibold text-[#111111] mt-1">
              {ov.totalHistoricalTestExecutions}
            </div>
            <div className="text-[11px] text-stone-500 mt-0.5">Actual CI records</div>
          </Panel>
          <Panel className="p-3.5">
            <div className="text-[10px] font-mono uppercase text-stone-500">Deployments</div>
            <div className="text-2xl font-mono font-semibold text-[#111111] mt-1">{ov.totalDeployments}</div>
            <div className="text-[11px] text-stone-500 mt-0.5">Recorded releases</div>
          </Panel>
          <Panel className="p-3.5">
            <div className="text-[10px] font-mono uppercase text-stone-500">Rollbacks</div>
            <div className="text-2xl font-mono font-semibold text-[#111111] mt-1">{ov.totalRollbacks}</div>
            <div className="text-[11px] text-stone-500 mt-0.5">Tracked separately</div>
          </Panel>
          <Panel className="p-3.5">
            <div className="text-[10px] font-mono uppercase text-stone-500">Confirmed Incidents</div>
            <div className="text-2xl font-mono font-semibold text-[#111111] mt-1">{ov.totalConfirmedIncidents}</div>
            <div className="text-[11px] text-stone-500 mt-0.5">Operational history</div>
          </Panel>
          <Panel className="p-3.5">
            <div className="text-[10px] font-mono uppercase text-stone-500">Confirmed Defects</div>
            <div className="text-2xl font-mono font-semibold text-[#111111] mt-1">{ov.totalConfirmedDefects}</div>
            <div className="text-[11px] text-stone-500 mt-0.5">Post-merge bugs</div>
          </Panel>
        </div>
      )}

      {/* PR Selector & Live Predictive Forecast Workbench */}
      <Panel className="p-4 space-y-4">
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-3 pb-3 border-b border-[#EFEFEA]">
          <div className="space-y-1">
            <div className="text-[10px] font-mono uppercase tracking-[0.12em] text-stone-500 flex items-center gap-1.5">
              <Gauge className="w-3.5 h-3.5 text-[#111111]" />
              Pull Request Predictive Risk Explorer
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <select
                aria-label="Select pull request review"
                value={selectedReviewId}
                onChange={(e) => handleSelectReview(e.target.value)}
                className="h-9 px-3 rounded-md border border-[#DCDCD5] bg-white text-xs font-medium min-w-[280px]"
              >
                <option value="">Select an analyzed Pull Request...</option>
                {(reviews.data ?? []).map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.repositoryName} #{r.prNumber} — {r.title}
                  </option>
                ))}
              </select>
              {selectedReviewId && (
                <>
                  <Link
                    to={`/reviews/${selectedReviewId}`}
                    className="h-9 px-3 rounded-md border border-[#DCDCD5] bg-white hover:bg-stone-50 text-xs font-medium inline-flex items-center gap-1"
                  >
                    Open Diff &amp; Change Risk <ArrowUpRight className="w-3.5 h-3.5" />
                  </Link>
                  <Link
                    to={pred ? `/code-intel?repo=${encodeURIComponent(pred.repositoryId)}` : '/code-intel'}
                    className="h-9 px-3 rounded-md border border-[#DCDCD5] bg-white hover:bg-stone-50 text-xs font-medium inline-flex items-center gap-1"
                  >
                    <Network className="w-3.5 h-3.5" /> Dependency Graph
                  </Link>
                  {canMutate && (
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() => recomputeMutation.mutate(selectedReviewId)}
                      disabled={recomputeMutation.isPending}
                    >
                      <RefreshCw className={`w-3.5 h-3.5 ${recomputeMutation.isPending ? 'animate-spin' : ''}`} />
                      Recompute Forecast
                    </Button>
                  )}
                </>
              )}
            </div>
          </div>

          {pred && (
            <div className="flex items-center gap-3 shrink-0">
              <div className="text-right">
                <div className="text-[10px] font-mono uppercase text-stone-500">Overall Engineering Risk</div>
                <div className="flex items-center justify-end gap-2 mt-0.5">
                  <span className="font-mono text-xl font-semibold text-[#111111]">
                    {pred.overallEngineeringRiskScore}
                    <span className="text-xs text-stone-400 font-normal">/100</span>
                  </span>
                  <Badge className={predictionRiskClasses(pred.overallRiskLevel)}>{pred.overallRiskLevel}</Badge>
                </div>
              </div>
            </div>
          )}
        </div>

        {!selectedReviewId ? (
          <div className="py-10 text-center text-xs text-stone-500">
            No pull requests have been reviewed yet. Analyze a PR in{' '}
            <Link to="/reviews" className="underline text-[#111111] font-medium">
              PR Reviews
            </Link>{' '}
            to generate explainable failure predictions.
          </div>
        ) : prPrediction.isError ? (
          <ErrorBanner
            message={
              prPrediction.error instanceof Error
                ? prPrediction.error.message
                : 'Failed to load prediction for selected pull request.'
            }
            onRetry={() => prPrediction.refetch()}
          />
        ) : prPrediction.isLoading ? (
          <SkeletonBlock className="h-48" />
        ) : pred ? (
          <div className="space-y-5">
            {/* Engine Metadata & Non-Blocking Policy Boundary */}
            <div className="rounded-lg bg-[#FAFAF8] border border-[#E5E5E0] p-3 text-xs space-y-1.5">
              <div className="flex flex-wrap items-center gap-x-4 gap-y-1 font-mono text-[11px] text-stone-600">
                <span>engine: {pred.engineVersion}</span>
                <span>mode: {pred.modelType}</span>
                <span>historical samples: {pred.historicalSampleSize}</span>
                <span>freshness: {formatRelativeTime(pred.dataFreshnessAt)}</span>
              </div>
              <div className="text-stone-700">{pred.dataAvailabilitySummary}</div>
              <div className="text-[11px] text-stone-500 border-t border-[#E5E5E0] pt-1.5">
                {pred.policyBoundaryNote}
              </div>
            </div>

            {/* 7 Separate Deduplicated Risk Signal Channels + Real Chart */}
            <div className="space-y-3">
              <h3 className="text-[11px] font-mono font-semibold uppercase tracking-[0.08em] text-[#111111] flex items-center gap-1.5">
                <Layers className="w-3.5 h-3.5" />
                7-Channel Signal Separation (Deduplicated Contribution)
              </h3>
              {pred.signalChannels.length > 0 && (
                <div className="rounded-lg border border-[#E5E5E0] bg-white p-3">
                  <div className="h-[190px] w-full">
                    <ResponsiveContainer width="100%" height="100%">
                      <BarChart
                        data={pred.signalChannels.map((c) => ({
                          name: c.label.replace('Static ', '').replace('Predicted ', '').slice(0, 18),
                          raw: c.rawScore,
                          dedup: c.deduplicatedContribution,
                        }))}
                        barGap={4}
                      >
                        <CartesianGrid strokeDasharray="3 3" stroke="#EAEAE4" vertical={false} />
                        <XAxis
                          dataKey="name"
                          tick={{ fill: '#57534E', fontSize: 10, fontFamily: 'JetBrains Mono' }}
                          axisLine={false}
                          tickLine={false}
                        />
                        <YAxis
                          domain={[0, 100]}
                          tick={{ fill: '#78716C', fontSize: 10, fontFamily: 'JetBrains Mono' }}
                          axisLine={false}
                          tickLine={false}
                          width={28}
                        />
                        <Tooltip
                          contentStyle={{
                            backgroundColor: '#FFFFFF',
                            border: '1px solid #E5E5E0',
                            borderRadius: 8,
                            fontSize: 11,
                          }}
                        />
                        <Bar dataKey="raw" name="Raw Channel Score" fill="#D6D3CD" radius={[3, 3, 0, 0]} />
                        <Bar dataKey="dedup" name="Deduplicated Contribution" fill="#111111" radius={[3, 3, 0, 0]} />
                      </BarChart>
                    </ResponsiveContainer>
                  </div>
                </div>
              )}
              <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-2.5">
                {pred.signalChannels.map((ch) => (
                  <div
                    key={ch.channel}
                    className="rounded-lg border border-[#E5E5E0] bg-white p-3 space-y-1.5"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-[11px] font-semibold text-[#111111]">{ch.label}</span>
                      <Badge className={predictionRiskClasses(ch.riskLevel)}>{ch.riskLevel}</Badge>
                    </div>
                    <div className="flex items-baseline justify-between font-mono text-[11px] text-stone-600">
                      <span>Raw: {ch.rawScore}/100</span>
                      <span className="font-semibold text-[#111111]">+{ch.deduplicatedContribution} pts</span>
                    </div>
                    <div className="h-1.5 w-full rounded-full bg-stone-100 overflow-hidden">
                      <div
                        className="h-full bg-[#111111]"
                        style={{ width: `${Math.min(100, ch.rawScore)}%` }}
                      />
                    </div>
                    <div className="text-[10.5px] text-stone-500">{ch.summary}</div>
                  </div>
                ))}
              </div>
            </div>

            {/* 5 Category Explainable Prediction Cards */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
              {pred.predictions.map((item) => (
                <div
                  key={item.id}
                  className="rounded-lg border border-[#E5E5E0] bg-white p-4 space-y-3 flex flex-col justify-between"
                >
                  <div className="space-y-2.5">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div>
                        <div className="text-xs font-semibold text-[#111111]">
                          {CATEGORY_LABELS[item.category] ?? item.category}
                        </div>
                        <div className="text-[10.5px] font-mono text-stone-500">
                          {item.ruleOrModelVersion} · samples={item.historicalSampleSize} · prob=
                          {item.probability != null ? `${Math.round(item.probability * 100)}%` : 'Omitted (uncalibrated)'}
                        </div>
                      </div>
                      <div className="flex items-center gap-1.5">
                        <Badge className={predictionRiskClasses(item.riskLevel)}>
                          {item.riskLevel} ({item.riskScore})
                        </Badge>
                        <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-[#FAFAF8] border border-[#E5E5E0] text-stone-700">
                          Conf: {item.confidenceLevel}
                        </span>
                      </div>
                    </div>

                    <p className="text-xs text-stone-800 leading-relaxed">{item.explanation}</p>

                    {item.evidence.length > 0 && (
                      <div className="space-y-1">
                        <div className="text-[10px] font-mono uppercase text-stone-500">
                          Supporting Evidence ({item.evidence.length})
                        </div>
                        <ul className="space-y-1">
                          {item.evidence.slice(0, 4).map((ev) => (
                            <li
                              key={ev.id}
                              className="text-[11px] text-stone-700 bg-[#FAFAF8] border border-[#EFEFEA] rounded px-2.5 py-1.5 flex items-start justify-between gap-2"
                            >
                              <span>{ev.summary}</span>
                              <span className="font-mono text-[10px] text-stone-500 shrink-0">
                                +{ev.weight}
                              </span>
                            </li>
                          ))}
                        </ul>
                      </div>
                    )}
                  </div>

                  <div className="space-y-1.5 pt-2 border-t border-[#EFEFEA] text-[11px]">
                    <div className="text-[#111111]">
                      <span className="font-mono uppercase text-[10px] text-stone-500 mr-1.5">Action:</span>
                      {item.recommendedAction}
                    </div>
                    <div className="text-stone-500">
                      <span className="font-mono uppercase text-[10px] mr-1.5">Limitations:</span>
                      {item.limitations}
                    </div>
                  </div>
                </div>
              ))}
            </div>

            {/* Scope Risk Breakdown (File / Class / Method / Module / Service) */}
            {evData && (
              <div className="rounded-lg border border-[#E5E5E0] bg-white p-4 space-y-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <h3 className="text-xs font-mono font-semibold uppercase tracking-[0.08em] text-[#111111]">
                    Risk Breakdown by Scope
                  </h3>
                  <div className="flex items-center gap-1 bg-[#FAFAF8] p-1 rounded-md border border-[#E5E5E0]">
                    {(['FILE', 'CLASS', 'METHOD', 'MODULE', 'SERVICE'] as BreakdownTab[]).map((tab) => (
                      <button
                        key={tab}
                        onClick={() => setBreakdownTab(tab)}
                        className={`px-2.5 py-1 rounded text-[10.5px] font-mono cursor-pointer ${
                          breakdownTab === tab ? 'bg-[#111111] text-white' : 'text-stone-600 hover:text-[#111111]'
                        }`}
                      >
                        {tab}
                      </button>
                    ))}
                  </div>
                </div>
                {prEvidence.isError ? (
                  <ErrorBanner message="Failed to load scope risk breakdown." onRetry={() => prEvidence.refetch()} />
                ) : activeScopeRows.length === 0 ? (
                  <div className="text-xs text-stone-500 py-3">
                    No {breakdownTab.toLowerCase()}-level breakdown records for this PR.
                  </div>
                ) : (
                  <div className="divide-y divide-[#EFEFEA]">
                    {activeScopeRows.slice(0, 10).map((row, idx) => {
                      const graphNode =
                        row.scope === 'CLASS'
                          ? row.name
                          : row.scope === 'FILE'
                            ? row.name.split('/').pop()?.replace(/\.(java|ts|tsx|js|jsx|py)$/i, '') ?? row.name
                            : '';
                      return (
                        <div key={`${row.scope}-${row.name}-${idx}`} className="py-2 flex items-start justify-between gap-3 text-xs">
                          <div className="min-w-0">
                            {graphNode && pred ? (
                              <Link
                                to={`/code-intel?repo=${encodeURIComponent(pred.repositoryId)}&node=${encodeURIComponent(graphNode)}`}
                                className="font-mono font-medium text-[#111111] hover:underline truncate inline-flex items-center gap-1"
                              >
                                {row.name}
                                <ArrowUpRight className="w-3 h-3 text-stone-400 shrink-0" />
                              </Link>
                            ) : (
                              <div className="font-mono font-medium text-[#111111] truncate">{row.name}</div>
                            )}
                            <div className="text-[11px] text-stone-600 mt-0.5">{row.reason}</div>
                          </div>
                          <Badge className={predictionRiskClasses(row.riskLevel)}>
                            {row.riskLevel} ({row.riskScore})
                          </Badge>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            )}

            {/* Predicted Tests at Risk vs Actual CI Results (Strictly Separated) */}
            {testsData && (
              <div className="rounded-lg border border-[#E5E5E0] bg-white p-4 space-y-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <h3 className="text-xs font-mono font-semibold uppercase tracking-[0.08em] text-[#111111] flex items-center gap-1.5">
                    <FlaskConical className="w-3.5 h-3.5" />
                    Test Failure Forecast vs Actual CI Test Results (PR #{testsData.prNumber})
                  </h3>
                  <Badge className="bg-[#111111] text-white border-[#111111]">
                    {testsData.hasActualCiResults ? 'ACTUAL CI DATA AVAILABLE' : 'FORECAST ONLY — NOT EXECUTED'}
                  </Badge>
                </div>
                <p className="text-[11px] text-stone-600 bg-[#FAFAF8] border border-[#EFEFEA] rounded px-3 py-2">
                  {testsData.separationNotice}
                </p>
                <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <div className="text-[10.5px] font-mono uppercase text-stone-500">
                      Predicted Tests at Risk ({testsData.predictedTestsAtRisk.length})
                    </div>
                    {testsData.predictedTestsAtRisk.length === 0 ? (
                      <div className="text-xs text-stone-500 border border-dashed border-[#DCDCD5] rounded p-3">
                        No candidate tests matched changed files.
                      </div>
                    ) : (
                      <div className="space-y-2">
                        {testsData.predictedTestsAtRisk.map((t) => (
                          <div key={t.testName} className="rounded border border-[#E5E5E0] p-2.5 text-xs space-y-1">
                            <div className="flex items-center justify-between gap-2">
                              <span className="font-mono font-semibold text-[#111111]">{t.testName}</span>
                              <Badge className={predictionRiskClasses(t.riskLevel)}>
                                {t.riskLevel} ({t.riskScore})
                              </Badge>
                            </div>
                            <div className="text-[11px] text-stone-700">{t.selectionReason}</div>
                            <div className="text-[10.5px] font-mono text-stone-500">
                              {t.historicalEvidence} · Conf: {t.confidenceLevel} · {t.predictionState}
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>

                  <div className="space-y-2">
                    <div className="text-[10.5px] font-mono uppercase text-stone-500">
                      Actual CI Test Results ({testsData.actualCiTestResults.length})
                    </div>
                    {testsData.actualCiTestResults.length === 0 ? (
                      <div className="text-xs text-stone-500 border border-dashed border-[#DCDCD5] rounded p-3">
                        No actual CI test executions recorded for PR #{testsData.prNumber} yet. Predicted tests are
                        never reported as passed or failed until CI reports actual results.
                      </div>
                    ) : (
                      <div className="space-y-2">
                        {testsData.actualCiTestResults.map((a) => (
                          <div key={a.id} className="rounded border border-[#E5E5E0] p-2.5 text-xs space-y-1">
                            <div className="flex items-center justify-between gap-2">
                              <span className="font-mono font-semibold text-[#111111]">{a.testName}</span>
                              <Badge className={statusClasses(a.outcome)}>{a.outcome}</Badge>
                            </div>
                            {a.errorSummary && <div className="text-[11px] text-red-700">{a.errorSummary}</div>}
                            <div className="text-[10.5px] font-mono text-stone-500">
                              {a.durationMs}ms · {formatRelativeTime(a.executedAt)}
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              </div>
            )}

            {prTests.isError && (
              <ErrorBanner message="Failed to load test failure forecast." onRetry={() => prTests.refetch()} />
            )}

            {/* PR Historical Evidence Context & Prediction Run History */}
            {prHistory.isError ? (
              <ErrorBanner message="Failed to load historical context for this PR." onRetry={() => prHistory.refetch()} />
            ) : histData ? (
              <div className="rounded-lg border border-[#E5E5E0] bg-[#FAFAF8] p-3.5 text-xs space-y-2">
                <div className="flex items-center justify-between">
                  <span className="font-mono text-[10.5px] uppercase text-stone-600 flex items-center gap-1.5">
                    <History className="w-3.5 h-3.5" />
                    Matched Historical Records for This PR ({histData.totalMatchingRecords})
                  </span>
                  <span className="font-mono text-[10.5px] text-stone-500">{histData.repositoryName}</span>
                </div>
                <div className="text-[11px] text-stone-600">{histData.availabilityNotice}</div>
              </div>
            ) : null}

            {prRuns.data && prRuns.data.length > 0 && (
              <div className="rounded-lg border border-[#E5E5E0] bg-white p-3.5 text-xs space-y-2">
                <div className="text-[10.5px] font-mono uppercase text-stone-500">
                  Prediction Run Version History ({prRuns.data.length})
                </div>
                <div className="flex flex-wrap gap-2">
                  {prRuns.data.slice(0, 6).map((r) => (
                    <div
                      key={r.predictionRunId}
                      className="rounded border border-[#E5E5E0] bg-[#FAFAF8] px-2.5 py-1.5 font-mono text-[10.5px] flex items-center gap-2"
                    >
                      <span className="text-stone-500">{r.commitSha.slice(0, 8)}</span>
                      <Badge className={predictionRiskClasses(r.overallRiskLevel)}>
                        {r.overallRiskLevel} ({r.overallEngineeringRiskScore})
                      </Badge>
                      <span className="text-stone-500">n={r.historicalSampleSize}</span>
                      <span className="text-stone-400">{formatRelativeTime(r.createdAt)}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        ) : null}
      </Panel>

      {/* Recent Workspace PR Forecasts Table */}
      {ov && ov.recentPredictions.length > 0 && (
        <Panel className="p-4 space-y-3">
          <div className="flex items-center justify-between">
            <h2 className="text-xs font-mono font-semibold uppercase tracking-[0.08em] text-[#111111]">
              Workspace PR Predictive Risk Register ({ov.recentPredictions.length})
            </h2>
            <span className="text-[11px] text-stone-500">Click any PR row to inspect evidence &amp; tests</span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-xs text-left">
              <thead>
                <tr className="border-b border-[#E5E5E0] text-[10px] font-mono uppercase text-stone-500">
                  <th className="py-2 pr-3">PR</th>
                  <th className="py-2 pr-3">Overall</th>
                  <th className="py-2 pr-3">Bug</th>
                  <th className="py-2 pr-3">Tests</th>
                  <th className="py-2 pr-3">Incident</th>
                  <th className="py-2 pr-3">Deploy/RB</th>
                  <th className="py-2 pr-3">Static Perf</th>
                  <th className="py-2">Samples</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[#EFEFEA]">
                {ov.recentPredictions.map((row) => (
                  <tr
                    key={row.predictionRunId}
                    onClick={() => handleSelectReview(row.reviewId)}
                    className={`cursor-pointer hover:bg-[#FAFAF8] transition-colors ${
                      row.reviewId === selectedReviewId ? 'bg-[#FAFAF8]' : ''
                    }`}
                  >
                    <td className="py-2 pr-3">
                      <div className="font-medium text-[#111111]">
                        #{row.prNumber} {row.prTitle}
                      </div>
                      <div className="text-[10.5px] font-mono text-stone-500">{row.repositoryName}</div>
                    </td>
                    <td className="py-2 pr-3">
                      <Badge className={predictionRiskClasses(row.overallRiskLevel)}>
                        {row.overallRiskLevel} ({row.overallEngineeringRiskScore})
                      </Badge>
                    </td>
                    <td className="py-2 pr-3">
                      <Badge className={predictionRiskClasses(row.bugRiskLevel)}>{row.bugRiskLevel}</Badge>
                    </td>
                    <td className="py-2 pr-3">
                      <Badge className={predictionRiskClasses(row.testFailureRiskLevel)}>{row.testFailureRiskLevel}</Badge>
                    </td>
                    <td className="py-2 pr-3">
                      <Badge className={predictionRiskClasses(row.incidentRiskLevel)}>{row.incidentRiskLevel}</Badge>
                    </td>
                    <td className="py-2 pr-3">
                      <Badge className={predictionRiskClasses(row.changeFailureRiskLevel)}>
                        {row.changeFailureRiskLevel}
                      </Badge>
                    </td>
                    <td className="py-2 pr-3">
                      <Badge className={predictionRiskClasses(row.performanceRiskLevel)}>
                        {row.performanceRiskLevel}
                      </Badge>
                    </td>
                    <td className="py-2 font-mono text-stone-600">{row.historicalSampleSize}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
      )}

      {/* Historical Insights Grid */}
      {ov && (
        <div className="grid grid-cols-1 lg:grid-cols-2 xl:grid-cols-4 gap-4">
          <Panel className="p-4 space-y-3">
            <h2 className="text-xs font-mono font-semibold uppercase tracking-[0.08em] text-[#111111] flex items-center gap-1.5">
              <AlertTriangle className="w-3.5 h-3.5" />
              Frequently Problematic Modules
            </h2>
            {ov.problematicModules.length === 0 ? (
              <p className="text-xs text-stone-500">
                No module defects, incidents, or test failures recorded yet.
              </p>
            ) : (
              <div className="divide-y divide-[#EFEFEA] text-xs">
                {ov.problematicModules.map((m) => (
                  <div key={`${m.repositoryName}-${m.moduleName}`} className="py-2 flex items-center justify-between">
                    <div>
                      <div className="font-mono font-medium text-[#111111]">{m.moduleName}</div>
                      <div className="text-[10.5px] text-stone-500">{m.repositoryName}</div>
                    </div>
                    <div className="font-mono text-[10.5px] text-stone-700 text-right">
                      {m.defectCount} bugs · {m.incidentCount} inc · {m.testFailureCount} fail
                    </div>
                  </div>
                ))}
              </div>
            )}
          </Panel>

          <Panel className="p-4 space-y-3">
            <h2 className="text-xs font-mono font-semibold uppercase tracking-[0.08em] text-[#111111] flex items-center gap-1.5">
              <Activity className="w-3.5 h-3.5" />
              Test Reliability History
            </h2>
            {ov.testReliability.length === 0 ? (
              <p className="text-xs text-stone-500">
                No historical test execution outcomes recorded yet.
              </p>
            ) : (
              <div className="divide-y divide-[#EFEFEA] text-xs">
                {ov.testReliability.map((t) => (
                  <div key={t.testName} className="py-2 flex items-center justify-between gap-2">
                    <div className="min-w-0">
                      <div className="font-mono font-medium text-[#111111] truncate">{t.testName}</div>
                      <div className="text-[10.5px] text-stone-500">
                        {t.totalRuns} runs · {t.failedRuns} failed · {t.flakyRuns} flaky
                      </div>
                    </div>
                    <span className="font-mono text-xs font-semibold text-[#111111] shrink-0">
                      {t.passRatePct}%
                    </span>
                  </div>
                ))}
              </div>
            )}
          </Panel>

          <Panel className="p-4 space-y-3">
            <h2 className="text-xs font-mono font-semibold uppercase tracking-[0.08em] text-[#111111] flex items-center gap-1.5">
              <Database className="w-3.5 h-3.5" />
              Deployment Outcomes &amp; Rollbacks
            </h2>
            {ov.deploymentOutcomes.length === 0 ? (
              <p className="text-xs text-stone-500">
                No deployment or rollback records ingested yet.
              </p>
            ) : (
              <div className="divide-y divide-[#EFEFEA] text-xs">
                {ov.deploymentOutcomes.map((d) => (
                  <div key={d.serviceName} className="py-2 flex items-center justify-between gap-2">
                    <div>
                      <div className="font-mono font-medium text-[#111111]">{d.serviceName}</div>
                      <div className="text-[10.5px] text-stone-500">
                        {d.totalDeployments} deploys · {d.failedDeployments} failed · {d.rollbacks} rollbacks
                      </div>
                    </div>
                    <span className="font-mono text-xs font-semibold text-[#111111]">
                      {d.successRatePct}%
                    </span>
                  </div>
                ))}
              </div>
            )}
          </Panel>

          <Panel className="p-4 space-y-3">
            <h2 className="text-xs font-mono font-semibold uppercase tracking-[0.08em] text-[#111111] flex items-center gap-1.5">
              <ShieldAlert className="w-3.5 h-3.5" />
              Recent Confirmed Incidents
            </h2>
            {ov.recentIncidents.length === 0 ? (
              <p className="text-xs text-stone-500">
                No confirmed production incidents recorded yet.
              </p>
            ) : (
              <div className="divide-y divide-[#EFEFEA] text-xs">
                {ov.recentIncidents.map((inc) => (
                  <div key={inc.id} className="py-2 space-y-0.5">
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-medium text-[#111111] truncate">{inc.title}</span>
                      <Badge className={predictionRiskClasses(inc.severity === 'SEV1' ? 'CRITICAL' : 'HIGH')}>
                        {inc.severity}
                      </Badge>
                    </div>
                    <div className="text-[10.5px] font-mono text-stone-500">
                      {inc.serviceName} · {inc.status} · {formatRelativeTime(inc.occurredAt)}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </Panel>
        </div>
      )}

      {/* Chronological Held-Out Model Evaluation */}
      {ov && (
        <Panel className="p-4 space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <h2 className="text-xs font-mono font-semibold uppercase tracking-[0.08em] text-[#111111] flex items-center gap-1.5">
                <ShieldAlert className="w-3.5 h-3.5" />
                Chronological Held-Out Prediction Evaluation
              </h2>
              <p className="text-[11px] text-stone-500 mt-0.5">
                Compares earliest (pre-outcome) versioned predictions strictly against confirmed post-prediction outcomes
                on the chronological held-out tail. Undefined class-imbalance ratios and insufficient-history categories
                are intentionally omitted rather than fabricated.
              </p>
            </div>
          </div>
          {ov.latestEvaluations.length === 0 ? (
            <div className="text-xs text-stone-500 py-3">
              No chronological evaluations have been executed yet. Click <strong>Run Held-Out Evaluation</strong> to
              evaluate stored predictions against real confirmed outcomes.
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-xs text-left">
                <thead>
                  <tr className="border-b border-[#E5E5E0] text-[10px] font-mono uppercase text-stone-500">
                    <th className="py-2 pr-3">Category</th>
                    <th className="py-2 pr-3">Status</th>
                    <th className="py-2 pr-3">Samples</th>
                    <th className="py-2 pr-3">Precision</th>
                    <th className="py-2 pr-3">Recall</th>
                    <th className="py-2 pr-3">F1</th>
                    <th className="py-2 pr-3">FPR / FNR</th>
                    <th className="py-2 pr-3">Cal. Error</th>
                    <th className="py-2">Methodology &amp; Notes</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#EFEFEA]">
                  {ov.latestEvaluations.map((ev) => {
                    const comp = ev.baselineComparison || {};
                    const imbalanceNote =
                      typeof comp.classImbalanceWarning === 'string' ? comp.classImbalanceWarning : null;
                    const excludedLeakage =
                      typeof comp.excludedPrePredictionOutcomes === 'number'
                        ? comp.excludedPrePredictionOutcomes
                        : 0;
                    const fprText = ev.falsePositiveRate != null ? ev.falsePositiveRate.toFixed(2) : '—';
                    const fnrText = ev.falseNegativeRate != null ? ev.falseNegativeRate.toFixed(2) : '—';
                    return (
                      <tr key={ev.id}>
                        <td className="py-2 pr-3 font-mono font-medium text-[#111111]">{ev.category}</td>
                        <td className="py-2 pr-3">
                          <Badge className={predictionRiskClasses(ev.status === 'EVALUATED' ? 'LOW' : 'INSUFFICIENT_DATA')}>
                            {ev.status}
                          </Badge>
                        </td>
                        <td className="py-2 pr-3 font-mono">
                          {ev.sampleSize}
                          <span className="text-[10px] text-stone-400"> ({ev.positiveOutcomes}+)</span>
                        </td>
                        <td className="py-2 pr-3 font-mono">{ev.precisionScore != null ? ev.precisionScore.toFixed(3) : '—'}</td>
                        <td className="py-2 pr-3 font-mono">{ev.recallScore != null ? ev.recallScore.toFixed(3) : '—'}</td>
                        <td className="py-2 pr-3 font-mono">{ev.f1Score != null ? ev.f1Score.toFixed(3) : '—'}</td>
                        <td className="py-2 pr-3 font-mono">
                          {ev.falsePositiveRate != null || ev.falseNegativeRate != null
                            ? `${fprText} / ${fnrText}`
                            : '—'}
                        </td>
                        <td className="py-2 pr-3 font-mono">
                          {ev.calibrationError != null ? ev.calibrationError.toFixed(3) : '—'}
                        </td>
                        <td className="py-2 text-[11px] text-stone-600 space-y-1">
                          <div>{ev.notes}</div>
                          {(imbalanceNote || excludedLeakage > 0) && (
                            <div className="flex flex-wrap gap-1.5 text-[10px] font-mono text-amber-800">
                              {excludedLeakage > 0 && (
                                <span className="px-1.5 py-0.5 rounded bg-amber-50 border border-amber-200">
                                  Anti-leakage: {excludedLeakage} pre-prediction outcome(s) excluded
                                </span>
                              )}
                              {imbalanceNote && (
                                <span className="px-1.5 py-0.5 rounded bg-stone-100 border border-stone-200 text-stone-700">
                                  {imbalanceNote}
                                </span>
                              )}
                            </div>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Panel>
      )}
    </div>
  );
}
