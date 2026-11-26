import { create } from 'zustand';
import { queryClient } from './queryClient';
import type {
  AuthResponse,
  OperationStatusSnapshot,
  PipelineEvent,
  SessionResponse,
  TenantDto,
  UserDto,
} from './types';

function inferEventStatus(ev: PipelineEvent): 'STARTED' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED' {
  const s = (ev.status || '').toUpperCase();
  if (s === 'STARTED' || s === 'IN_PROGRESS' || s === 'COMPLETED' || s === 'FAILED') {
    return s;
  }
  const stage = (ev.stage || '').toUpperCase();
  if (stage === 'COMPLETED' || stage.endsWith('_COMPLETED')) return 'COMPLETED';
  if (stage === 'FAILED' || stage.endsWith('_FAILED')) return 'FAILED';
  if (stage === 'QUEUED' || stage === 'STARTED') return 'STARTED';
  return 'IN_PROGRESS';
}

export function resolveOperationId(ev: PipelineEvent): string {
  return ev.operationId || `${ev.eventType}:${ev.resourceId}`;
}

/**
 * Pure, deterministic state reducer that merges an incoming SSE PipelineEvent into
 * an OperationStatusSnapshot with strict eventId deduplication, out-of-order sequence
 * protection, and terminal-state immutability (unless restarted by a fresh STARTED seq=1 event).
 */
export function applyPipelineEventToOperation(
  existing: OperationStatusSnapshot | undefined,
  rawEvent: PipelineEvent
): OperationStatusSnapshot {
  const opId = resolveOperationId(rawEvent);
  const eventStatus = inferEventStatus(rawEvent);
  const seq = typeof rawEvent.sequence === 'number' ? rawEvent.sequence : 0;
  const nowTs = rawEvent.timestamp || new Date().toISOString();
  const normalized: PipelineEvent = {
    ...rawEvent,
    operationId: opId,
    status: eventStatus,
    stageLabel: rawEvent.stageLabel || rawEvent.stage,
    timestamp: nowTs,
  };

  const isFreshRestart =
    existing &&
    (existing.status === 'COMPLETED' || existing.status === 'FAILED') &&
    eventStatus === 'STARTED' &&
    (seq === 1 || seq === 0);

  if (!existing || isFreshRestart) {
    const terminal = eventStatus === 'COMPLETED' || eventStatus === 'FAILED';
    const totalStages = normalized.totalStages ?? 0;
    return {
      operationId: opId,
      eventType: normalized.eventType,
      resourceId: normalized.resourceId,
      correlationId: normalized.correlationId || '',
      status: terminal ? eventStatus : 'RUNNING',
      currentStage: normalized.stage,
      currentStageLabel: normalized.stageLabel || normalized.stage,
      completedStages: eventStatus === 'COMPLETED' ? (totalStages > 0 ? totalStages : 1) : 0,
      totalStages,
      latestSequence: seq > 0 ? seq : 1,
      startedAt: nowTs,
      updatedAt: nowTs,
      completedAt: terminal ? nowTs : null,
      events: [normalized],
    };
  }

  // Deduplicate exact eventId or duplicate sequence+stage frames
  if (normalized.eventId && existing.events.some((e) => e.eventId === normalized.eventId)) {
    return existing;
  }
  if (seq > 0 && existing.events.some((e) => e.sequence === seq && e.stage === normalized.stage)) {
    return existing;
  }

  const mergedEvents = [...existing.events, normalized].sort((a, b) => {
    const seqA = a.sequence ?? 0;
    const seqB = b.sequence ?? 0;
    if (seqA !== seqB && seqA > 0 && seqB > 0) return seqA - seqB;
    return (a.timestamp || '').localeCompare(b.timestamp || '');
  });

  const isStaleOutOfOrder = seq > 0 && existing.latestSequence > 0 && seq < existing.latestSequence;
  const alreadyTerminal = existing.status === 'COMPLETED' || existing.status === 'FAILED';

  // Preserve terminal status & never regress head state when late/out-of-order frames arrive
  if (isStaleOutOfOrder || (alreadyTerminal && eventStatus !== 'COMPLETED' && eventStatus !== 'FAILED')) {
    return {
      ...existing,
      events: mergedEvents.slice(-32),
    };
  }

  const nextStatus =
    eventStatus === 'COMPLETED'
      ? 'COMPLETED'
      : eventStatus === 'FAILED'
        ? 'FAILED'
        : 'RUNNING';
  const totalStages = Math.max(existing.totalStages, normalized.totalStages ?? 0);
  const completedStages =
    nextStatus === 'COMPLETED'
      ? totalStages > 0
        ? totalStages
        : mergedEvents.length
      : Math.max(existing.completedStages, Math.max(0, mergedEvents.length - 1));

  return {
    ...existing,
    correlationId: normalized.correlationId || existing.correlationId,
    status: nextStatus,
    currentStage: normalized.stage,
    currentStageLabel: normalized.stageLabel || normalized.stage,
    completedStages,
    totalStages,
    latestSequence: seq > 0 ? Math.max(existing.latestSequence, seq) : existing.latestSequence + 1,
    updatedAt: nowTs,
    completedAt: nextStatus === 'COMPLETED' || nextStatus === 'FAILED' ? nowTs : existing.completedAt,
    events: mergedEvents.slice(-32),
  };
}

interface ForgeState {
  token: string | null;
  refreshToken: string | null;
  user: UserDto | null;
  tenant: TenantDto | null;
  initialWebhookSecret: string | null;
  sseConnected: boolean;
  sseReconnecting: boolean;
  latestPipelineEvent: PipelineEvent | null;
  pipelineHistory: PipelineEvent[];
  operationsById: Record<string, OperationStatusSnapshot>;
  commandOpen: boolean;
  setSession: (auth: AuthResponse) => void;
  updateSessionInfo: (session: SessionResponse) => void;
  updateTenant: (tenant: TenantDto) => void;
  dismissInitialWebhookSecret: () => void;
  clearSession: () => void;
  setSseConnected: (connected: boolean, reconnecting?: boolean) => void;
  pushPipelineEvent: (ev: PipelineEvent) => void;
  hydrateOperations: (snapshots: OperationStatusSnapshot[]) => void;
  setCommandOpen: (open: boolean) => void;
}

function loadStoredAuth(): {
  token: string | null;
  refreshToken: string | null;
  user: UserDto | null;
  tenant: TenantDto | null;
} {
  try {
    const raw = localStorage.getItem('forge_session');
    if (!raw) return { token: null, refreshToken: null, user: null, tenant: null };
    const parsed = JSON.parse(raw);
    return {
      token: parsed.token ?? null,
      refreshToken: parsed.refreshToken ?? null,
      user: parsed.user ?? null,
      tenant: parsed.tenant ?? null,
    };
  } catch {
    return { token: null, refreshToken: null, user: null, tenant: null };
  }
}

const initial = loadStoredAuth();

export const useForgeStore = create<ForgeState>((set) => ({
  token: initial.token,
  refreshToken: initial.refreshToken,
  user: initial.user,
  tenant: initial.tenant,
  initialWebhookSecret: null,
  sseConnected: false,
  sseReconnecting: false,
  latestPipelineEvent: null,
  pipelineHistory: [],
  operationsById: {},
  commandOpen: false,

  setSession: (auth) => {
    // Purge React Query cache and transient pipeline history whenever switching sessions/tenants
    queryClient.clear();
    localStorage.setItem(
      'forge_session',
      JSON.stringify({
        token: auth.accessToken,
        refreshToken: auth.refreshToken,
        user: auth.user,
        tenant: auth.tenant,
      })
    );
    set({
      token: auth.accessToken,
      refreshToken: auth.refreshToken,
      user: auth.user,
      tenant: auth.tenant,
      initialWebhookSecret: auth.initialWebhookSecret ?? null,
      latestPipelineEvent: null,
      pipelineHistory: [],
      operationsById: {},
    });
  },

  updateSessionInfo: (session) =>
    set((state) => {
      if (state.token) {
        localStorage.setItem(
          'forge_session',
          JSON.stringify({
            token: state.token,
            refreshToken: state.refreshToken,
            user: session.user,
            tenant: session.tenant,
          })
        );
      }
      return { user: session.user, tenant: session.tenant };
    }),

  updateTenant: (tenant) =>
    set((state) => {
      if (state.token && state.user) {
        localStorage.setItem(
          'forge_session',
          JSON.stringify({
            token: state.token,
            refreshToken: state.refreshToken,
            user: state.user,
            tenant,
          })
        );
      }
      return { tenant };
    }),

  dismissInitialWebhookSecret: () => set({ initialWebhookSecret: null }),

  clearSession: () => {
    localStorage.removeItem('forge_session');
    queryClient.clear();
    set({
      token: null,
      refreshToken: null,
      user: null,
      tenant: null,
      initialWebhookSecret: null,
      sseConnected: false,
      sseReconnecting: false,
      latestPipelineEvent: null,
      pipelineHistory: [],
      operationsById: {},
    });
  },

  setSseConnected: (connected, reconnecting = false) =>
    set({ sseConnected: connected, sseReconnecting: connected ? false : reconnecting }),

  pushPipelineEvent: (ev) =>
    set((state) => {
      if (ev.eventType === 'CONNECTED') {
        return state;
      }
      const opId = resolveOperationId(ev);
      const updatedOp = applyPipelineEventToOperation(state.operationsById[opId], ev);
      const nextOps = { ...state.operationsById, [opId]: updatedOp };
      // Bound client store to latest 40 operations
      const keys = Object.keys(nextOps);
      if (keys.length > 40) {
        keys
          .sort((a, b) => (nextOps[a].updatedAt || '').localeCompare(nextOps[b].updatedAt || ''))
          .slice(0, keys.length - 40)
          .forEach((k) => delete nextOps[k]);
      }
      const latestEv = updatedOp.events[updatedOp.events.length - 1] ?? ev;
      return {
        latestPipelineEvent: latestEv,
        pipelineHistory: [latestEv, ...state.pipelineHistory.filter((h) => h.eventId !== latestEv.eventId)].slice(0, 30),
        operationsById: nextOps,
      };
    }),

  hydrateOperations: (snapshots) =>
    set((state) => {
      if (!snapshots || snapshots.length === 0) return state;
      const nextOps = { ...state.operationsById };
      for (const snap of snapshots) {
        const existing = nextOps[snap.operationId];
        if (!existing || snap.latestSequence >= existing.latestSequence) {
          nextOps[snap.operationId] = snap;
        }
      }
      return { operationsById: nextOps };
    }),

  setCommandOpen: (open) => set({ commandOpen: open }),
}));
