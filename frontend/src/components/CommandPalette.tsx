import { useEffect, useRef, useState } from 'react';
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
  { label: 'Engineering Overview', path: '/', chord: 'o', shortcut: 'G O', icon: LayoutDashboard, adminOnly: false },
  { label: 'Repositories & Webhooks', path: '/repositories', chord: 'r', shortcut: 'G R', icon: FolderGit2, adminOnly: false },
  { label: 'Code Intelligence & AST Graph', path: '/code-intel', chord: 'c', shortcut: 'G C', icon: Network, adminOnly: false },
  { label: 'Pull Request Reviews & Change Risk', path: '/reviews', chord: 'p', shortcut: 'G P', icon: GitPullRequest, adminOnly: false },
  { label: 'Predictive Failure Intelligence', path: '/predictions', chord: 'f', shortcut: 'G F', icon: Activity, adminOnly: false },
  { label: 'Security Center (Secrets, SCA & SARIF)', path: '/security', chord: 's', shortcut: 'G S', icon: ShieldAlert, adminOnly: false },
  { label: 'CI Failure Intelligence', path: '/ci', chord: 'i', shortcut: 'G I', icon: Activity, adminOnly: false },
  { label: 'Engineering Analytics & Eval Harness', path: '/analytics', chord: 'a', shortcut: 'G A', icon: BarChart3, adminOnly: false },
  { label: 'Immutable Audit Logs', path: '/audit', chord: 'l', shortcut: 'G L', icon: FileClock, adminOnly: true },
  { label: 'Workspace Settings & Risk Policies', path: '/settings', chord: ',', shortcut: 'G ,', icon: Settings, adminOnly: true },
];

export function CommandPalette() {
  const open = useForgeStore((s) => s.commandOpen);
  const setOpen = useForgeStore((s) => s.setCommandOpen);
  const user = useForgeStore((s) => s.user);
  const [query, setQuery] = useState('');
  const [activeIdx, setActiveIdx] = useState(0);
  const gPressedAtRef = useRef<number>(0);
  const navigate = useNavigate();

  const isAdmin = user?.role === 'OWNER' || user?.role === 'ADMIN';
  const availableCommands = COMMANDS.filter((c) => !c.adminOnly || isAdmin);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setOpen(!open);
        return;
      }

      if (open || e.metaKey || e.ctrlKey || e.altKey) return;
      const target = e.target as HTMLElement | null;
      const tag = target?.tagName?.toLowerCase();
      if (tag === 'input' || tag === 'textarea' || tag === 'select' || target?.isContentEditable) {
        return;
      }

      const key = e.key.toLowerCase();
      const now = Date.now();
      if (key === 'g') {
        gPressedAtRef.current = now;
        return;
      }
      if (now - gPressedAtRef.current <= 900) {
        const match = availableCommands.find((cmd) => cmd.chord === key);
        if (match) {
          e.preventDefault();
          gPressedAtRef.current = 0;
          navigate(match.path);
        }
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [open, setOpen, navigate, availableCommands]);

  const filtered = availableCommands.filter((c) =>
    c.label.toLowerCase().includes(query.trim().toLowerCase())
  );

  useEffect(() => {
    setActiveIdx(0);
  }, [query, open]);

  const selectCommand = (path: string) => {
    navigate(path);
    setOpen(false);
    setQuery('');
  };

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
              aria-label="Jump to workspace view"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'ArrowDown' && filtered.length > 0) {
                  e.preventDefault();
                  setActiveIdx((i) => (i + 1) % filtered.length);
                } else if (e.key === 'ArrowUp' && filtered.length > 0) {
                  e.preventDefault();
                  setActiveIdx((i) => (i - 1 + filtered.length) % filtered.length);
                } else if (e.key === 'Enter' && filtered[activeIdx]) {
                  e.preventDefault();
                  selectCommand(filtered[activeIdx].path);
                }
              }}
              placeholder="Jump to workspace view or module..."
              className="w-full bg-transparent text-sm text-[#111111] placeholder:text-stone-400 focus:outline-none"
            />
            <kbd className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-stone-100 border border-stone-200 text-stone-500">
              ESC
            </kbd>
          </div>
          <div className="max-h-80 overflow-y-auto p-1.5" role="listbox">
            {filtered.length === 0 ? (
              <div className="py-8 text-center text-xs text-stone-500">No matching destinations.</div>
            ) : (
              filtered.map((cmd, idx) => {
                const Icon = cmd.icon;
                const isActive = idx === activeIdx;
                return (
                  <button
                    key={cmd.path}
                    role="option"
                    aria-selected={isActive}
                    onMouseEnter={() => setActiveIdx(idx)}
                    onClick={() => selectCommand(cmd.path)}
                    className={`w-full flex items-center justify-between px-3 py-2.5 rounded-lg text-left text-xs transition-colors cursor-pointer ${
                      isActive ? 'bg-[#111111] text-white' : 'text-stone-800 hover:bg-[#F5F5F0]'
                    }`}
                  >
                    <span className="flex items-center gap-2.5">
                      <Icon className={`w-4 h-4 ${isActive ? 'text-white' : 'text-stone-500'}`} />
                      <span className="font-medium">{cmd.label}</span>
                    </span>
                    <kbd className={`text-[10px] font-mono ${isActive ? 'text-stone-300' : 'text-stone-400'}`}>
                      {cmd.shortcut}
                    </kbd>
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
