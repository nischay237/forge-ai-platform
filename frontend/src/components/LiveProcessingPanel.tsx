import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  AlertCircle,
  ArrowUpRight,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Circle,
  Clock,
  Cpu,
  FileCode2,
  GitCompare,
  Layers,
  Loader2,
  Network,
  Radar,
  RefreshCw,
  Scale,
  ShieldCheck,
  Sparkles,
  Terminal,
  WifiOff,
  type LucideIcon,
} from 'lucide-react';
import { api } from '../lib/api';
import { useForgeStore } from '../lib/store';
import type { OperationStatusSnapshot, PipelineEvent } from '../lib/types';
import { cn } from '../lib/utils';

interface StageSpec {
  code: string;
  label: string;
  icon: LucideIcon;
}

const PIPELINE_STAGE_CATALOG: Record<string, StageSpec[]> = {
  PR_REVIEW: [
    { code: 'QUEUED', label: 'Request accepted & queued', icon: Clock },
    { code: 'INGESTING_DIFF', label: 'Parsing unified diff hunks', icon: GitCompare },
    { code: 'AST_IMPACT_ANALYSIS', label: 'Computing AST dependency blast radius', icon: Network },
    { code: 'CONTRACT_AND_MIGRATION_ANALYSIS', label: 'Checking REST API & SQL migration safety', icon: Layers },
    { code: 'SECURITY_SCANNING', label: 'Scanning secrets, SAST & prompt-injection patterns', icon: ShieldCheck },
    { code: 'AI_CONTEXT_REVIEW', label: 'Synthesizing contextual review findings', icon: Sparkles },
    { code: 'RISK_AND_TEST_SELECTION', label: 'Evaluating policy merge decision & impacted tests', icon: Scale },
    { code: 'COMPLETED', label: 'Review complete & persisted', icon: CheckCircle2 },
  ],
  PR_PREDICTION: [
    { code: 'EXTRACTING_FEATURES', label: 'Extracting diff, AST & historical signals', icon: Cpu },
    { code: 'EVALUATING_DEFECT_AND_TEST_RISK', label: 'Evaluating bug & test failure risk', icon: Radar },
    { code: 'EVALUATING_OPERATIONAL_AND_PERF_RISK', label: 'Evaluating operational & static perf risk', icon: Layers },
    { code: 'AGGREGATING_AND_PERSISTING', label: 'Deduplicating signal channels & persisting evidence', icon: Scale },
    { code: 'PREDICTION_COMPLETED', label: 'Predictive baseline ready', icon: CheckCircle2 },
  ],
  REPO_INDEX: [
    { code: 'LOADING_SOURCE_FILES', label: 'Loading repository source files', icon: FileCode2 },
    { code: 'PARSING_AST', label: 'Parsing Java & TypeScript/JS AST', icon: Cpu },
    { code: 'BUILDING_DEPENDENCY_GRAPH', label: 'Resolving dependency edges & cycles', icon: Network },
    { code: 'COMPLETED', label: 'AST index & dependency graph ready', icon: CheckCircle2 },
  ],
};

const EVENT_TYPE_TITLES: Record<string, string> = {
  PR_REVIEW: 'Pull Request Review Pipeline',
  PR_PREDICTION: 'Predictive Risk Engine',
  REPO_INDEX: 'Repository AST & Dependency Indexer',
};

function formatElapsed(ms: number): string {
  const safe = Math.max(0, ms);
  if (safe < 1000) return `${safe}ms`;
  const seconds = safe / 1000;
  if (seconds < 60) return `${seconds.toFixed(1)}s`;
  const mins = Math.floor(seconds / 60);
  const rem = (seconds % 60).toFixed(0).padStart(2, '0');
  return `${mins}m ${rem}s`;
}

function formatClock(iso?: string | null): string {
  if (!iso) return '--:--:--';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '--:--:--';
  return d.toLocaleTimeString([], { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function formatDetailValue(val: unknown): string {
  if (val === null || val === undefined) return '—';
  if (typeof val === 'object') return JSON.stringify(val);
  return String(val);
}

export interface LiveProcessingPanelProps {
  /** Resource ID (reviewId or repositoryId) to track. */
  resourceId?: string;
  /** Event types to match (e.g. ['PR_REVIEW', 'PR_PREDICTION']). */
  eventTypes?: string[];
  /** Optional explicit snapshot (useful for controlled views & unit tests). */
  snapshot?: OperationStatusSnapshot | null;
  /** Optional retry callback rendered when status === 'FAILED'. */
  onRetry?: () => void;
  /** Optional navigation CTA rendered on terminal completion. */
  completionAction?: { label: string; to: string };
  /** Start collapsed once operation is already completed. */
  collapseWhenCompleted?: boolean;
  /** Auto-fetch backend operation snapshot on mount for page-refresh recovery. */
  autoRecover?: boolean;
  className?: string;
}

export function LiveProcessingPanel({
  resourceId,
  eventTypes,
  snapshot: controlledSnapshot,
  onRetry,
  completionAction,
  collapseWhenCompleted = false,
  autoRecover = true,
  className,
}: LiveProcessingPanelProps) {
  const operationsById = useForgeStore((s) => s.operationsById);
  const hydrateOperations = useForgeStore((s) => s.hydrateOperations);
  const sseConnected = useForgeStore((s) => s.sseConnected);
  const sseReconnecting = useForgeStore((s) => s.sseReconnecting);

  const operation = useMemo(() => {
    if (controlledSnapshot !== undefined) {
      return controlledSnapshot;
    }
    const candidates = Object.values(operationsById).filter((op) => {
      if (resourceId && op.resourceId !== resourceId) return false;
      if (eventTypes && eventTypes.length > 0 && !eventTypes.includes(op.eventType)) return false;
      return true;
    });
    if (candidates.length === 0) return null;
    // Prefer actively RUNNING operation over completed ones, then most recently updated
    candidates.sort((a, b) => {
      if (a.status === 'RUNNING' && b.status !== 'RUNNING') return -1;
      if (b.status === 'RUNNING' && a.status !== 'RUNNING') return 1;
      return (b.updatedAt || '').localeCompare(a.updatedAt || '');
    });
    return candidates[0];
  }, [controlledSnapshot, operationsById, resourceId, eventTypes]);

  const [expanded, setExpanded] = useState(true);
  const [showDiagnostics, setShowDiagnostics] = useState(false);
  const [nowMs, setNowMs] = useState(() => Date.now());

  // Recover exact operation timeline from backend on page refresh
  useEffect(() => {
    if (!autoRecover || controlledSnapshot !== undefined || !resourceId) return;
    if (!useForgeStore.getState().token) return;
    let active = true;
    api
      .listStreamOperations(resourceId)
      .then((snaps) => {
        if (active && snaps.length > 0) {
          hydrateOperations(snaps);
        }
      })
      .catch(() => {
        // non-blocking recovery
      });
    return () => {
      active = false;
    };
  }, [autoRecover, controlledSnapshot, resourceId, hydrateOperations]);

  // Collapse automatically if configured and already completed on first sight
  useEffect(() => {
    if (operation?.status === 'RUNNING' || operation?.status === 'FAILED') {
      setExpanded(true);
    } else if (collapseWhenCompleted && operation?.status === 'COMPLETED') {
      setExpanded(false);
    }
  }, [operation?.operationId, operation?.status, collapseWhenCompleted]);

  // Real wall-clock elapsed ticker ONLY while operation is actively RUNNING
  useEffect(() => {
    if (!operation || operation.status !== 'RUNNING') return;
    setNowMs(Date.now());
    const id = setInterval(() => setNowMs(Date.now()), 250);
    return () => clearInterval(id);
  }, [operation?.operationId, operation?.status]);

  if (!operation) {
    return null;
  }

  const catalog = PIPELINE_STAGE_CATALOG[operation.eventType] ?? [];
  const eventsByStage = new Map<string, PipelineEvent>();
  for (const ev of operation.events) {
    eventsByStage.set(ev.stage, ev);
  }

  // Merge catalog stages with any additional backend-emitted stages (e.g. FAILED)
  const timelineStages: Array<{
    code: string;
    label: string;
    icon: LucideIcon;
    event?: PipelineEvent;
    state: 'completed' | 'active' | 'pending' | 'failed';
  }> = [];

  const catalogCodes = new Set(catalog.map((s) => s.code));
  const receivedOrder = operation.events.map((e) => e.stage);
  const currentIdxInCatalog = catalog.findIndex((s) => s.code === operation.currentStage);

  for (let i = 0; i < catalog.length; i++) {
    const spec = catalog[i];
    const ev = eventsByStage.get(spec.code);
    let state: 'completed' | 'active' | 'pending' | 'failed' = 'pending';

    if (operation.status === 'COMPLETED') {
      state = 'completed';
    } else if (operation.status === 'FAILED') {
      if (ev && ev.status === 'FAILED') {
        state = 'failed';
      } else if (ev || (currentIdxInCatalog > i && currentIdxInCatalog !== -1)) {
        state = 'completed';
      } else {
        state = 'pending';
      }
    } else {
      // RUNNING
      if (spec.code === operation.currentStage) {
        state = 'active';
      } else if (
        (currentIdxInCatalog !== -1 && i < currentIdxInCatalog) ||
        (ev && receivedOrder.indexOf(spec.code) < receivedOrder.length - 1)
      ) {
        state = 'completed';
      } else {
        state = 'pending';
      }
    }

    timelineStages.push({
      code: spec.code,
      label: ev?.stageLabel || spec.label,
      icon: spec.icon,
      event: ev,
      state,
    });
  }

  // Append any non-catalog events (such as FAILED or custom stage)
  for (const ev of operation.events) {
    if (!catalogCodes.has(ev.stage)) {
      timelineStages.push({
        code: ev.stage,
        label: ev.stageLabel || ev.stage,
        icon: ev.status === 'FAILED' ? AlertCircle : Terminal,
        event: ev,
        state:
          ev.status === 'FAILED'
            ? 'failed'
            : ev.status === 'COMPLETED'
              ? 'completed'
              : ev.stage === operation.currentStage
                ? 'active'
                : 'completed',
      });
    }
  }

  const totalStages = operation.totalStages > 0 ? operation.totalStages : catalog.length || timelineStages.length;
  const completedCount = timelineStages.filter((s) => s.state === 'completed').length;
  const startedMs = new Date(operation.startedAt).getTime();
  const endedMs = operation.completedAt ? new Date(operation.completedAt).getTime() : nowMs;
  const elapsedMs = Number.isNaN(startedMs) ? 0 : Math.max(0, endedMs - startedMs);
  const latestEvent = operation.events[operation.events.length - 1];
  const isRunning = operation.status === 'RUNNING';
  const isFailed = operation.status === 'FAILED';
  const isCompleted = operation.status === 'COMPLETED';
  const title = EVENT_TYPE_TITLES[operation.eventType] || operation.eventType;

  return (
    <section
      role="region"
      aria-label={`${title} live execution timeline`}
      className={cn(
        'rounded-xl bg-[#0D0E12] border border-[#23262F] text-zinc-100 shadow-lg overflow-hidden',
        className
      )}
    >
      {/* Top Status Bar */}
      <div className="px-4 py-3 sm:px-5 flex flex-wrap items-center justify-between gap-3 border-b border-[#1E212A] bg-[#111319]">
        <div className="flex items-center gap-3 min-w-0">
          <div
            className={cn(
              'w-8 h-8 rounded-lg flex items-center justify-center shrink-0 border',
              isRunning && 'bg-indigo-500/15 border-indigo-400/40 text-indigo-300',
              isCompleted && 'bg-emerald-500/15 border-emerald-400/40 text-emerald-300',
              isFailed && 'bg-red-500/15 border-red-400/40 text-red-300'
            )}
          >
            {isRunning ? (
              <Loader2 className="w-4 h-4 animate-spin motion-reduce:animate-none" />
            ) : isCompleted ? (
              <CheckCircle2 className="w-4 h-4" />
            ) : (
              <AlertCircle className="w-4 h-4" />
            )}
          </div>
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="text-xs sm:text-[13px] font-semibold text-zinc-100 tracking-[-0.01em]">
                {title}
              </h2>
              <span
                className={cn(
                  'inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[10px] font-mono uppercase tracking-[0.06em] border',
                  isRunning && 'bg-indigo-500/15 border-indigo-400/35 text-indigo-200',
                  isCompleted && 'bg-emerald-500/15 border-emerald-400/35 text-emerald-200',
                  isFailed && 'bg-red-500/15 border-red-400/35 text-red-200'
                )}
              >
                {isRunning && (
                  <span className="w-1.5 h-1.5 rounded-full bg-indigo-400 animate-pulse motion-reduce:animate-none" />
                )}
                {isRunning
                  ? `Stage ${Math.min(completedCount + 1, totalStages)} of ${totalStages}`
                  : isCompleted
                    ? `Completed · ${totalStages} stages`
                    : 'Failed'}
              </span>
              {sseReconnecting && isRunning && (
                <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-mono bg-amber-500/15 border border-amber-400/35 text-amber-200">
                  <WifiOff className="w-3 h-3" />
                  Reconnecting stream
                </span>
              )}
            </div>
            <p aria-live="polite" className="text-[11.5px] text-zinc-400 mt-0.5 truncate">
              {latestEvent?.message || operation.currentStageLabel}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2 sm:gap-3 shrink-0">
          <div className="text-right font-mono text-[11px] text-zinc-400 tabular-nums">
            <span className="text-zinc-200 font-medium">{formatElapsed(elapsedMs)}</span>
            <span className="mx-1.5 text-zinc-600">·</span>
            <span>{sseConnected ? 'SSE live' : 'Snapshot'}</span>
          </div>

          <button
            type="button"
            onClick={() => setShowDiagnostics((v) => !v)}
            aria-expanded={showDiagnostics}
            className="h-7 px-2.5 rounded-md border border-[#2A2E39] bg-[#151821] hover:bg-[#1C202B] text-[11px] font-mono text-zinc-300 transition-colors cursor-pointer"
          >
            {showDiagnostics ? 'Hide Details' : 'Details'}
          </button>

          <button
            type="button"
            onClick={() => setExpanded((v) => !v)}
            aria-expanded={expanded}
            aria-label={expanded ? 'Collapse processing timeline' : 'Expand processing timeline'}
            className="h-7 w-7 rounded-md border border-[#2A2E39] bg-[#151821] hover:bg-[#1C202B] text-zinc-300 inline-flex items-center justify-center transition-colors cursor-pointer"
          >
            {expanded ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
          </button>
        </div>
      </div>

      {/* Real Stage Segmented Bar (discrete stage segments — NEVER fake percentages) */}
      <div className="px-4 sm:px-5 py-2 bg-[#0B0C10] border-b border-[#1A1D26] flex items-center gap-1" aria-hidden="true">
        {timelineStages.map((s) => (
          <div
            key={s.code}
            className={cn(
              'h-1 flex-1 rounded-full transition-colors duration-300',
              s.state === 'completed' && 'bg-emerald-500/90',
              s.state === 'active' && 'bg-indigo-400 animate-pulse motion-reduce:animate-none',
              s.state === 'failed' && 'bg-red-500',
              s.state === 'pending' && 'bg-[#22252F]'
            )}
          />
        ))}
      </div>

      {/* Connected Vertical Stage Timeline */}
      {expanded && (
        <div className="px-4 sm:px-5 py-4 space-y-4">
          <ol className="relative space-y-0">
            {timelineStages.map((stage, idx) => {
              const Icon = stage.icon;
              const isLast = idx === timelineStages.length - 1;
              const detailEntries = stage.event?.details
                ? Object.entries(stage.event.details).filter(([, v]) => v !== null && v !== undefined && v !== '')
                : [];

              return (
                <li key={stage.code} className="relative flex items-start gap-3 pb-3.5 last:pb-0">
                  {!isLast && (
                    <span
                      aria-hidden="true"
                      className={cn(
                        'absolute left-[11px] top-6 bottom-0 w-px',
                        stage.state === 'completed' ? 'bg-emerald-500/40' : 'bg-[#252834]'
                      )}
                    />
                  )}

                  <div
                    className={cn(
                      'relative z-10 mt-0.5 w-6 h-6 rounded-full flex items-center justify-center border shrink-0 transition-colors',
                      stage.state === 'completed' && 'bg-emerald-500/15 border-emerald-500/50 text-emerald-400',
                      stage.state === 'active' &&
                        'bg-indigo-500/20 border-indigo-400 text-indigo-300 shadow-[0_0_12px_rgba(99,102,241,0.35)]',
                      stage.state === 'failed' && 'bg-red-500/20 border-red-400 text-red-300',
                      stage.state === 'pending' && 'bg-[#12141B] border-[#292D3A] text-zinc-600'
                    )}
                  >
                    {stage.state === 'completed' ? (
                      <CheckCircle2 className="w-3.5 h-3.5" />
                    ) : stage.state === 'active' ? (
                      <Loader2 className="w-3.5 h-3.5 animate-spin motion-reduce:animate-none" />
                    ) : stage.state === 'failed' ? (
                      <AlertCircle className="w-3.5 h-3.5" />
                    ) : (
                      <Circle className="w-2.5 h-2.5" />
                    )}
                  </div>

                  <div
                    className={cn(
                      'flex-1 min-w-0 rounded-lg px-3 py-2 border transition-colors',
                      stage.state === 'active'
                        ? 'bg-indigo-500/[0.08] border-indigo-400/30'
                        : stage.state === 'failed'
                          ? 'bg-red-500/[0.08] border-red-400/30'
                          : 'bg-transparent border-transparent'
                    )}
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="flex items-center gap-2 min-w-0">
                        <Icon
                          className={cn(
                            'w-3.5 h-3.5 shrink-0',
                            stage.state === 'completed' && 'text-emerald-400',
                            stage.state === 'active' && 'text-indigo-300',
                            stage.state === 'failed' && 'text-red-400',
                            stage.state === 'pending' && 'text-zinc-600'
                          )}
                        />
                        <span
                          className={cn(
                            'text-xs font-medium truncate',
                            stage.state === 'pending' ? 'text-zinc-500' : 'text-zinc-100'
                          )}
                        >
                          {stage.label}
                        </span>
                      </div>
                      <span className="font-mono text-[10.5px] text-zinc-500 tabular-nums shrink-0">
                        {stage.event?.timestamp ? formatClock(stage.event.timestamp) : stage.state === 'pending' ? 'waiting' : ''}
                      </span>
                    </div>

                    {stage.event?.message && (
                      <p
                        className={cn(
                          'text-[11px] mt-1 leading-relaxed',
                          stage.state === 'failed' ? 'text-red-200' : 'text-zinc-400'
                        )}
                      >
                        {stage.event.message}
                      </p>
                    )}

                    {detailEntries.length > 0 && (showDiagnostics || stage.state === 'active' || stage.state === 'failed' || stage.code === 'COMPLETED' || stage.code === 'PREDICTION_COMPLETED') && (
                      <div className="flex flex-wrap gap-1.5 mt-2">
                        {detailEntries.map(([k, v]) => (
                          <span
                            key={k}
                            className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-[#161922] border border-[#262A36] font-mono text-[10px] text-zinc-300"
                          >
                            <span className="text-zinc-500">{k}:</span>
                            <span>{formatDetailValue(v)}</span>
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                </li>
              );
            })}
          </ol>

          {/* Expandable Technical Trace Diagnostics */}
          {showDiagnostics && (
            <div className="rounded-lg bg-[#090A0D] border border-[#1E212B] p-3 font-mono text-[10.5px] text-zinc-400 space-y-1.5">
              <div className="flex flex-wrap gap-x-4 gap-y-1">
                <span>
                  <span className="text-zinc-600">operationId:</span> {operation.operationId}
                </span>
                {operation.correlationId && (
                  <span>
                    <span className="text-zinc-600">correlationId:</span> {operation.correlationId}
                  </span>
                )}
                <span>
                  <span className="text-zinc-600">seq:</span> {operation.latestSequence}
                </span>
                <span>
                  <span className="text-zinc-600">started:</span> {formatClock(operation.startedAt)}
                </span>
              </div>
              <div className="text-zinc-500">
                Stages reflect real server-emitted pipeline transitions; no simulated timers or fabricated percentages are used.
              </div>
            </div>
          )}

          {/* Terminal Footer: Retry on Error or Action Link on Completion */}
          {(isFailed || (isCompleted && completionAction)) && (
            <div className="pt-3 border-t border-[#1E212A] flex flex-wrap items-center justify-between gap-3">
              {isFailed ? (
                <>
                  <span className="text-xs text-red-300">
                    Pipeline halted with an error. Inspect stage details above or retry execution.
                  </span>
                  {onRetry && (
                    <button
                      type="button"
                      onClick={onRetry}
                      className="h-8 px-3 rounded-md bg-red-500/20 hover:bg-red-500/30 border border-red-400/40 text-red-100 text-xs font-medium inline-flex items-center gap-1.5 transition-colors cursor-pointer"
                    >
                      <RefreshCw className="w-3.5 h-3.5" />
                      Retry Operation
                    </button>
                  )}
                </>
              ) : completionAction ? (
                <>
                  <span className="text-xs text-emerald-300">
                    Completed in {formatElapsed(elapsedMs)} ({totalStages} verified backend stages).
                  </span>
                  <Link
                    to={completionAction.to}
                    className="h-8 px-3 rounded-md bg-zinc-100 hover:bg-white text-[#0D0E12] text-xs font-medium inline-flex items-center gap-1.5 transition-colors"
                  >
                    {completionAction.label}
                    <ArrowUpRight className="w-3.5 h-3.5" />
                  </Link>
                </>
              ) : null}
            </div>
          )}
        </div>
      )}
    </section>
  );
}
