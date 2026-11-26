import { create } from 'zustand';
import { queryClient } from './queryClient';
import type { AuthResponse, PipelineEvent, SessionResponse, TenantDto, UserDto } from './types';

interface ForgeState {
  token: string | null;
  refreshToken: string | null;
  user: UserDto | null;
  tenant: TenantDto | null;
  initialWebhookSecret: string | null;
  sseConnected: boolean;
  latestPipelineEvent: PipelineEvent | null;
  pipelineHistory: PipelineEvent[];
  commandOpen: boolean;
  setSession: (auth: AuthResponse) => void;
  updateSessionInfo: (session: SessionResponse) => void;
  updateTenant: (tenant: TenantDto) => void;
  dismissInitialWebhookSecret: () => void;
  clearSession: () => void;
  setSseConnected: (connected: boolean) => void;
  pushPipelineEvent: (ev: PipelineEvent) => void;
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
  latestPipelineEvent: null,
  pipelineHistory: [],
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
      latestPipelineEvent: null,
      pipelineHistory: [],
    });
  },

  setSseConnected: (connected) => set({ sseConnected: connected }),

  pushPipelineEvent: (ev) =>
    set((state) => ({
      latestPipelineEvent: ev,
      pipelineHistory: [ev, ...state.pipelineHistory].slice(0, 20),
    })),

  setCommandOpen: (open) => set({ commandOpen: open }),
}));
