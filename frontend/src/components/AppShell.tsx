import React from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { Command, LogOut, Plus } from 'lucide-react';
import { useForgeStore } from '../lib/store';
import { usePipelineStream } from '../hooks/usePipelineStream';
import { CommandPalette } from './CommandPalette';
import { Button, ForgeMark } from './ui';
import { cn } from '../lib/utils';

const NAV_ITEMS = [
  { to: '/', label: 'Overview', exact: true },
  { to: '/repositories', label: 'Repositories' },
  { to: '/code-intel', label: 'Code Intelligence' },
  { to: '/reviews', label: 'PR Reviews' },
  { to: '/security', label: 'Security' },
  { to: '/ci', label: 'CI Diagnosis' },
  { to: '/analytics', label: 'Analytics & Eval' },
  { to: '/audit', label: 'Audit Logs' },
  { to: '/settings', label: 'Settings' },
];

export function AppShell() {
  usePipelineStream();
  const user = useForgeStore((s) => s.user);
  const tenant = useForgeStore((s) => s.tenant);
  const sseConnected = useForgeStore((s) => s.sseConnected);
  const latestEvent = useForgeStore((s) => s.latestPipelineEvent);
  const clearSession = useForgeStore((s) => s.clearSession);
  const setCommandOpen = useForgeStore((s) => s.setCommandOpen);
  const navigate = useNavigate();

  return (
    <div className="min-h-screen swiss-grid text-[#111111] flex flex-col">
      <CommandPalette />

      {/* Crisp Vercel / GitHub Primer Sticky Top Chrome */}
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
                  sseConnected ? 'bg-emerald-600' : 'bg-stone-400'
                )}
              />
              {sseConnected ? 'SSE Live' : 'Idle'}
            </span>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={() => setCommandOpen(true)}
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
              <span>Analyze PR</span>
            </Button>

            <div className="hidden sm:flex items-center gap-2 pl-2 ml-1 border-l border-[#E5E5E0]">
              <div className="text-right leading-tight">
                <div className="text-xs font-medium text-[#111111]">{user?.fullName}</div>
                <div className="text-[10px] font-mono text-stone-500">{user?.role}</div>
              </div>
              <button
                onClick={() => {
                  clearSession();
                  navigate('/login');
                }}
                title="Sign out"
                className="h-8 w-8 rounded-md border border-[#E2E2DC] bg-white hover:bg-stone-100 text-stone-600 flex items-center justify-center cursor-pointer"
              >
                <LogOut className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
        </div>

        {/* Clean Horizontal Underline Navigation */}
        <div className="max-w-[1360px] mx-auto px-4 sm:px-6 border-t border-[#F0F0EC] flex items-center justify-between overflow-x-auto">
          <nav className="flex items-center gap-1">
            {NAV_ITEMS.map((item) => (
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
            Java 21 · Spring Boot 3.5 · Kafka Outbox · Redis
          </span>
        </div>

        {/* Live SSE Pipeline Progress Strip */}
        {latestEvent && latestEvent.stage !== 'CONNECTED' && (
          <div className="border-t border-[#E5E5E0] bg-[#FAFAF8] px-4 sm:px-6 py-2">
            <div className="max-w-[1360px] mx-auto flex items-center justify-between gap-4 text-xs">
              <div className="flex items-center gap-2.5 min-w-0">
                <span className="font-mono text-[10px] font-semibold px-1.5 py-0.5 rounded bg-[#111111] text-white shrink-0">
                  {latestEvent.stage}
                </span>
                <span className="text-stone-700 truncate">{latestEvent.message}</span>
              </div>
              <div className="flex items-center gap-2 shrink-0">
                <div className="w-24 h-1.5 rounded-full bg-stone-200 overflow-hidden">
                  <div
                    className="h-full bg-[#111111] transition-all duration-300"
                    style={{ width: `${latestEvent.progressPercent}%` }}
                  />
                </div>
                <span className="font-mono text-[11px] text-stone-600 tabular-nums">
                  {latestEvent.progressPercent}%
                </span>
              </div>
            </div>
          </div>
        )}
      </header>

      {/* Main Content Canvas */}
      <main className="flex-1 max-w-[1360px] w-full mx-auto px-4 sm:px-6 py-7">
        <Outlet />
      </main>
    </div>
  );
}
