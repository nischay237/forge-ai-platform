import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { Command, LogOut, Plus } from 'lucide-react';
import { api } from '../lib/api';
import { useForgeStore } from '../lib/store';
import { usePipelineStream } from '../hooks/usePipelineStream';
import { CommandPalette } from './CommandPalette';
import { Button, ForgeMark } from './ui';
import { cn } from '../lib/utils';

const NAV_ITEMS = [
  { to: '/', label: 'Overview', exact: true, adminOnly: false },
  { to: '/repositories', label: 'Repositories', exact: false, adminOnly: false },
  { to: '/code-intel', label: 'Code Intelligence', exact: false, adminOnly: false },
  { to: '/reviews', label: 'PR Reviews', exact: false, adminOnly: false },
  { to: '/predictions', label: 'Predictions', exact: false, adminOnly: false },
  { to: '/security', label: 'Security', exact: false, adminOnly: false },
  { to: '/ci', label: 'CI Diagnosis', exact: false, adminOnly: false },
  { to: '/analytics', label: 'Analytics & Eval', exact: false, adminOnly: false },
  { to: '/audit', label: 'Audit Logs', exact: false, adminOnly: true },
  { to: '/settings', label: 'Settings', exact: false, adminOnly: true },
];

export function AppShell() {
  usePipelineStream();
  const user = useForgeStore((s) => s.user);
  const tenant = useForgeStore((s) => s.tenant);
  const refreshToken = useForgeStore((s) => s.refreshToken);
  const sseConnected = useForgeStore((s) => s.sseConnected);
  const sseReconnecting = useForgeStore((s) => s.sseReconnecting);
  const latestEvent = useForgeStore((s) => s.latestPipelineEvent);
  const operationsById = useForgeStore((s) => s.operationsById);
  const clearSession = useForgeStore((s) => s.clearSession);
  const setCommandOpen = useForgeStore((s) => s.setCommandOpen);
  const navigate = useNavigate();

  const activeOperation = latestEvent
    ? operationsById[latestEvent.operationId || `${latestEvent.eventType}:${latestEvent.resourceId}`]
    : undefined;

  const isAdmin = user?.role === 'OWNER' || user?.role === 'ADMIN';
  const visibleNav = NAV_ITEMS.filter((item) => !item.adminOnly || isAdmin);

  const handleLogout = async () => {
    try {
      await api.logout(refreshToken);
    } catch {
      // Clear local session even if backend is unreachable
    } finally {
      clearSession();
      navigate('/login');
    }
  };

  return (
    <div className="min-h-screen swiss-grid text-[#111111] flex flex-col">
      <CommandPalette />

      <header className="sticky top-0 z-30 bg-white/95 backdrop-blur-md border-b border-[#E5E5E0]">
        <div className="max-w-[1360px] mx-auto px-4 sm:px-6 h-14 flex items-center justify-between gap-4">
          <div className="flex items-center gap-2.5 min-w-0">
            <button
              onClick={() => navigate('/')}
              className="flex items-center gap-2 cursor-pointer"
            >
              <ForgeMark className="w-6 h-6" />
              <span className="font-semibold text-sm tracking-[-0.02em] text-[#111111]">
                Forge
              </span>
            </button>
            <span className="text-stone-300 font-light">/</span>
            <span className="font-mono text-xs text-stone-600 truncate">
              {tenant?.name ?? 'Workspace'}
            </span>
            <span className="hidden sm:inline-flex items-center gap-1.5 ml-2 px-2 py-0.5 rounded-full bg-[#FAFAF8] border border-[#E5E5E0] text-[10.5px] font-mono text-stone-600">
              <span
                className={cn(
                  'w-1.5 h-1.5 rounded-full',
                  sseConnected
                    ? 'bg-emerald-600'
                    : sseReconnecting
                      ? 'bg-amber-500 animate-pulse motion-reduce:animate-none'
                      : 'bg-stone-400'
                )}
              />
              {sseConnected ? 'SSE Live' : sseReconnecting ? 'Reconnecting' : 'Idle'}
            </span>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={() => setCommandOpen(true)}
              aria-label="Open command palette"
              className="hidden md:flex items-center gap-2 h-8 px-2.5 rounded-md bg-[#FAFAF8] border border-[#E2E2DC] hover:border-stone-400 text-xs text-stone-500 transition-colors cursor-pointer"
            >
              <Command className="w-3.5 h-3.5" />
              <span>Search...</span>
              <kbd className="ml-2 text-[10px] font-mono px-1.5 py-0.2 rounded bg-white border border-[#E2E2DC] text-stone-500">
                ⌘K
              </kbd>
            </button>

            <Button size="sm" onClick={() => navigate('/reviews?new=1')}>
              <Plus className="w-3.5 h-3.5" />
              <span className="hidden xs:inline">Analyze PR</span>
            </Button>

            <div className="flex items-center gap-2 pl-2 ml-1 border-l border-[#E5E5E0]">
              <div className="hidden sm:block text-right leading-tight">
                <div className="text-xs font-medium text-[#111111]">{user?.fullName}</div>
                <div className="text-[10px] font-mono text-stone-500">{user?.role}</div>
              </div>
              <button
                onClick={handleLogout}
                title="Sign out"
                aria-label="Sign out"
                className="h-8 w-8 rounded-md border border-[#E2E2DC] bg-white hover:bg-stone-100 text-stone-600 flex items-center justify-center cursor-pointer"
              >
                <LogOut className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
        </div>

        <div className="max-w-[1360px] mx-auto px-4 sm:px-6 border-t border-[#F0F0EC] flex items-center justify-between overflow-x-auto">
          <nav aria-label="Primary navigation" className="flex items-center gap-1">
            {visibleNav.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.exact}
                className={({ isActive }) =>
                  cn(
                    'relative h-10 px-3 text-xs font-medium whitespace-nowrap flex items-center transition-colors',
                    isActive ? 'text-[#111111]' : 'text-stone-500 hover:text-[#111111]'
                  )
                }
              >
                {({ isActive }) => (
                  <>
                    <span>{item.label}</span>
                    {isActive && (
                      <span className="absolute bottom-0 left-2 right-2 h-[2px] bg-[#111111]" />
                    )}
                  </>
                )}
              </NavLink>
            ))}
          </nav>
          <span className="hidden xl:block font-mono text-[10.5px] text-stone-400">
            Change Impact &amp; Risk Intelligence · Java 21 · Spring Boot 3.5
          </span>
        </div>

        {latestEvent && latestEvent.stage !== 'CONNECTED' && (
          <div className="border-t border-[#1E212A] bg-[#0E1015] text-zinc-100 px-4 sm:px-6 py-2">
            <div className="max-w-[1360px] mx-auto flex items-center justify-between gap-4 text-xs">
              <div className="flex items-center gap-2.5 min-w-0">
                <span
                  className={cn(
                    'font-mono text-[10px] font-semibold px-1.5 py-0.5 rounded shrink-0 uppercase',
                    activeOperation?.status === 'FAILED'
                      ? 'bg-red-500/20 text-red-200 border border-red-400/35'
                      : activeOperation?.status === 'COMPLETED'
                        ? 'bg-emerald-500/20 text-emerald-200 border border-emerald-400/35'
                        : 'bg-indigo-500/20 text-indigo-200 border border-indigo-400/35'
                  )}
                >
                  {latestEvent.stageLabel || latestEvent.stage}
                </span>
                <span className="text-zinc-300 truncate">{latestEvent.message}</span>
              </div>
              <div className="flex items-center gap-3 shrink-0">
                {activeOperation && activeOperation.totalStages > 0 && (
                  <span className="font-mono text-[11px] text-zinc-400 tabular-nums">
                    {activeOperation.status === 'COMPLETED'
                      ? `${activeOperation.totalStages}/${activeOperation.totalStages} stages`
                      : `Stage ${Math.min(activeOperation.completedStages + 1, activeOperation.totalStages)}/${activeOperation.totalStages}`}
                  </span>
                )}
                {latestEvent.eventType === 'PR_REVIEW' && (
                  <button
                    type="button"
                    onClick={() => navigate(`/reviews/${latestEvent.resourceId}`)}
                    className="text-[11px] font-mono text-indigo-300 hover:text-indigo-200 underline cursor-pointer"
                  >
                    Inspect
                  </button>
                )}
                {latestEvent.eventType === 'PR_PREDICTION' && (
                  <button
                    type="button"
                    onClick={() => navigate(`/predictions?reviewId=${encodeURIComponent(latestEvent.resourceId)}`)}
                    className="text-[11px] font-mono text-indigo-300 hover:text-indigo-200 underline cursor-pointer"
                  >
                    Inspect
                  </button>
                )}
              </div>
            </div>
          </div>
        )}
      </header>

      <main className="flex-1 max-w-[1360px] w-full mx-auto px-4 sm:px-6 py-7">
        <Outlet />
      </main>

      <footer className="border-t border-[#E5E5E0] bg-white/80">
        <div className="max-w-[1360px] mx-auto px-4 sm:px-6 py-3 flex flex-wrap items-center justify-between gap-3 text-[11px] text-stone-500">
          <span className="font-mono">FORGE · Change Impact &amp; Predictive Risk Control Plane</span>
          <div className="flex items-center gap-5">
            <Link to="/showcase" className="hover:text-[#111111] transition-colors">
              Product Overview
            </Link>
            <Link to="/engineering" className="hover:text-[#111111] transition-colors">
              Engineering &amp; Reliability
            </Link>
          </div>
        </div>
      </footer>
    </div>
  );
}
