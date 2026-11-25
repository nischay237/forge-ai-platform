import React, { useEffect, useState } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { useNavigate } from 'react-router-dom';
import {
  Activity,
  BarChart3,
  FileClock,
  FolderGit2,
  GitPullRequest,
  LayoutDashboard,
  Network,
  Search,
  Settings,
  ShieldAlert,
} from 'lucide-react';
import { useForgeStore } from '../lib/store';

const COMMANDS = [
  { label: 'Engineering Overview', path: '/', shortcut: 'G O', icon: LayoutDashboard },
  { label: 'Repositories & Webhooks', path: '/repositories', shortcut: 'G R', icon: FolderGit2 },
  { label: 'Code Intelligence & AST Graph', path: '/code-intel', shortcut: 'G C', icon: Network },
  { label: 'Pull Request Reviews', path: '/reviews', shortcut: 'G P', icon: GitPullRequest },
  { label: 'Security Center (Secrets & SAST)', path: '/security', shortcut: 'G S', icon: ShieldAlert },
  { label: 'CI Failure Intelligence', path: '/ci', shortcut: 'G I', icon: Activity },
  { label: 'Engineering Analytics & AI Eval', path: '/analytics', shortcut: 'G A', icon: BarChart3 },
  { label: 'Immutable Audit Logs', path: '/audit', shortcut: 'G L', icon: FileClock },
  { label: 'Workspace Settings & RBAC', path: '/settings', shortcut: 'G ,', icon: Settings },
];

export function CommandPalette() {
  const open = useForgeStore((s) => s.commandOpen);
  const setOpen = useForgeStore((s) => s.setCommandOpen);
  const [query, setQuery] = useState('');
  const navigate = useNavigate();

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setOpen(!open);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [open, setOpen]);

  const filtered = COMMANDS.filter((c) =>
    c.label.toLowerCase().includes(query.trim().toLowerCase())
  );

  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/35 backdrop-blur-[2px]" />
        <Dialog.Content className="fixed left-1/2 top-[16%] z-50 w-full max-w-lg -translate-x-1/2 rounded-xl bg-white border border-[#DCDCD5] shadow-2xl overflow-hidden focus:outline-none">
          <Dialog.Title className="sr-only">Forge Command Palette</Dialog.Title>
          <div className="flex items-center gap-2.5 px-4 py-3 border-b border-[#EFEFEA]">
            <Search className="w-4 h-4 text-stone-400" />
            <input
              autoFocus
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Jump to workspace view or module..."
              className="w-full bg-transparent text-sm text-[#111111] placeholder:text-stone-400 focus:outline-none"
            />
            <kbd className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-stone-100 border border-stone-200 text-stone-500">
              ESC
            </kbd>
          </div>
          <div className="max-h-80 overflow-y-auto p-1.5">
            {filtered.length === 0 ? (
              <div className="py-8 text-center text-xs text-stone-500">No matching destinations.</div>
            ) : (
              filtered.map((cmd) => {
                const Icon = cmd.icon;
                return (
                  <button
                    key={cmd.path}
                    onClick={() => {
                      navigate(cmd.path);
                      setOpen(false);
                      setQuery('');
                    }}
                    className="w-full flex items-center justify-between px-3 py-2.5 rounded-lg text-left text-xs text-stone-800 hover:bg-[#F5F5F0] transition-colors cursor-pointer"
                  >
                    <span className="flex items-center gap-2.5">
                      <Icon className="w-4 h-4 text-stone-500" />
                      <span className="font-medium">{cmd.label}</span>
                    </span>
                    <kbd className="text-[10px] font-mono text-stone-400">{cmd.shortcut}</kbd>
                  </button>
                );
              })
            )}
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
