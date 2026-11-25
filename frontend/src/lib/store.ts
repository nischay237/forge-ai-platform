import { create } from 'zustand';
import type { AuthResponse, PipelineEvent, TenantDto, UserDto } from './types';

interface ForgeState {
  token: string | null;
  user: UserDto | null;
  tenant: TenantDto | null;
  sseConnected: boolean;
  latestPipelineEvent: PipelineEvent | null;
  pipelineHistory: PipelineEvent[];
  commandOpen: boolean;
  setSession: (auth: AuthResponse) => void;
  updateTenant: (tenant: TenantDto) => void;
  clearSession: () => void;
  setSseConnected: (connected: boolean) => void;
  pushPipelineEvent: (ev: PipelineEvent) => void;
  setCommandOpen: (open: boolean) => void;
}

function loadStoredAuth(): { token: string | null; user: UserDto | null; tenant: TenantDto | null } {
  try {
    const raw = localStorage.getItem('forge_session');
    if (!raw) return { token: null, user: null, tenant: null };
    const parsed = JSON.parse(raw);
    return {
      token: parsed.token ?? null,
      user: parsed.user ?? null,
      tenant: parsed.tenant ?? null,
    };
  } catch {
    return { token: null, user: null, tenant: null };
  }
}

const initial = loadStoredAuth();

export const useForgeStore = create<ForgeState>((set) => ({
  token: initial.token,
  user: initial.user,
  tenant: initial.tenant,
  sseConnected: false,
  latestPipelineEvent: null,
  pipelineHistory: [],
  commandOpen: false,

  setSession: (auth) => {
    localStorage.setItem(
      'forge_session',
      JSON.stringify({ token: auth.accessToken, user: auth.user, tenant: auth.tenant })
    );
    set({ token: auth.accessToken, user: auth.user, tenant: auth.tenant });
  },

  updateTenant: (tenant) =>
    set((state) => {
      if (state.token && state.user) {
        localStorage.setItem(
          'forge_session',
          JSON.stringify({ token: state.token, user: state.user, tenant })
        );
      }
      return { tenant };
    }),

  clearSession: () => {
    localStorage.removeItem('forge_session');
    set({ token: null, user: null, tenant: null, sseConnected: false, latestPipelineEvent: null });
  },

  setSseConnected: (connected) => set({ sseConnected: connected }),

  pushPipelineEvent: (ev) =>
    set((state) => ({
      latestPipelineEvent: ev,
      pipelineHistory: [ev, ...state.pipelineHistory].slice(0, 20),
    })),

  setCommandOpen: (open) => set({ commandOpen: open }),
}));
