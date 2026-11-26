import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import * as Dialog from '@radix-ui/react-dialog';
import {
  ExternalLink,
  FolderGit2,
  GitBranch,
  Network,
  Plus,
  RefreshCw,
  Shield,
  Trash2,
  Webhook,
  X,
} from 'lucide-react';
import { api } from '../lib/api';
import type { RepositoryDto } from '../lib/types';
import { LiveProcessingPanel } from '../components/LiveProcessingPanel';
import { Badge, Button, EmptyState, ErrorBanner, PageHeader, Panel, SkeletonBlock } from '../components/ui';
import { formatRelativeTime, statusClasses, toSafeGithubUrl } from '../lib/utils';

const GITHUB_SLUG_REGEX = /^[A-Za-z0-9_][A-Za-z0-9_.-]*$/;

interface ConnectForm {
  owner: string;
  name: string;
  defaultBranch: string;
  primaryLanguage: string;
  description: string;
  fetchFromGithub: boolean;
}

const DEFAULT_CONNECT_FORM: ConnectForm = {
  owner: 'acme-fintech',
  name: 'fraud-scoring-engine',
  defaultBranch: 'main',
  primaryLanguage: 'Java',
  description: 'Real-time transaction velocity & fraud scoring microservice',
  fetchFromGithub: false,
};

export function RepositoriesPage() {
  const [open, setOpen] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<RepositoryDto | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [form, setForm] = useState<ConnectForm>(DEFAULT_CONNECT_FORM);
  const [formErrors, setFormErrors] = useState<{ owner?: string; name?: string }>({});
  const navigate = useNavigate();
  const qc = useQueryClient();

  const repos = useQuery({ queryKey: ['repos'], queryFn: api.listRepos });

  const reset = () => {
    setForm(DEFAULT_CONNECT_FORM);
    setFormErrors({});
  };

  const handleConnectSubmit = (e: FormEvent) => {
    e.preventDefault();
    const nextErrors: { owner?: string; name?: string } = {};
    const owner = form.owner.trim();
    const name = form.name.trim();
    if (!owner) {
      nextErrors.owner = 'GitHub owner/org is required';
    } else if (!GITHUB_SLUG_REGEX.test(owner)) {
      nextErrors.owner = 'Valid GitHub handle only';
    }
    if (!name) {
      nextErrors.name = 'Repository name is required';
    } else if (!GITHUB_SLUG_REGEX.test(name)) {
      nextErrors.name = 'Valid repo slug only';
    }
    if (nextErrors.owner || nextErrors.name) {
      setFormErrors(nextErrors);
      return;
    }
    setFormErrors({});
    connectMutation.mutate({
      owner,
      name,
      defaultBranch: form.defaultBranch.trim() || 'main',
      primaryLanguage: form.primaryLanguage || 'Java',
      description: form.description.trim() || undefined,
      fetchFromGithub: form.fetchFromGithub,
    });
  };

  const connectMutation = useMutation({
    mutationFn: api.connectRepo,
    onSuccess: (repo) => {
      qc.invalidateQueries({ queryKey: ['repos'] });
      qc.invalidateQueries({ queryKey: ['overview'] });
      setOpen(false);
      setActionError(null);
      reset();
      setToast(`Connected ${repo.fullName} — queued AST indexing via Transactional Outbox.`);
    },
  });

  const reindexMutation = useMutation({
    mutationFn: api.reindexRepo,
    onSuccess: (repo) => {
      qc.invalidateQueries({ queryKey: ['repos'] });
      setActionError(null);
      setToast(`Re-indexing ${repo.fullName} via AST worker.`);
    },
    onError: (err: unknown) => {
      setActionError(err instanceof Error ? err.message : 'Failed to queue repository re-indexing');
    },
  });

  const scanMutation = useMutation({
    mutationFn: api.scanRepositorySecurity,
    onSuccess: (findings) => {
      qc.invalidateQueries({ queryKey: ['security'] });
      qc.invalidateQueries({ queryKey: ['overview'] });
      setActionError(null);
      setToast(`Security & SCA scan complete (${findings.length} total findings tracked).`);
    },
    onError: (err: unknown) => {
      setActionError(err instanceof Error ? err.message : 'Security scan failed');
    },
  });

  const deleteMutation = useMutation({
    mutationFn: api.deleteRepo,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['repos'] });
      qc.invalidateQueries({ queryKey: ['overview'] });
      qc.invalidateQueries({ queryKey: ['reviews'] });
      setPendingDelete(null);
      setActionError(null);
      setToast('Disconnected repository and purged associated workspace index records.');
    },
    onError: (err: unknown) => {
      setPendingDelete(null);
      setActionError(err instanceof Error ? err.message : 'Failed to disconnect repository');
    },
  });

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="GitHub Integration & Ingestion"
        title="Connected"
        accentWord="repositories."
        description="Connect GitHub repositories for per-tenant HMAC-signed pull request webhooks, Java/TypeScript AST indexing, dependency graph extraction, and SCA/security scanning."
        actions={
          <Button onClick={() => setOpen(true)}>
            <Plus className="w-3.5 h-3.5" />
            Connect Repository
          </Button>
        }
      />

      {toast && (
        <div className="rounded-lg bg-emerald-50 border border-emerald-200 px-4 py-2.5 text-xs text-emerald-900 flex items-center justify-between">
          <span>{toast}</span>
          <button
            onClick={() => setToast(null)}
            aria-label="Dismiss notification"
            className="text-emerald-700 hover:text-emerald-950 cursor-pointer"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        </div>
      )}

      <LiveProcessingPanel
        eventTypes={['REPO_INDEX']}
        collapseWhenCompleted
        autoRecover={false}
      />

      {actionError && <ErrorBanner message={actionError} />}

      {repos.isError && (
        <ErrorBanner
          message={repos.error instanceof Error ? repos.error.message : 'Could not load repositories'}
          onRetry={() => repos.refetch()}
        />
      )}

      {repos.isLoading ? (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <SkeletonBlock className="h-48" />
          <SkeletonBlock className="h-48" />
        </div>
      ) : (repos.data ?? []).length === 0 ? (
        <EmptyState
          icon={FolderGit2}
          title="No repositories connected yet"
          description="Connect a GitHub repository or provision a starter codebase to unlock AST dependency graphs, Change Risk analysis, and SBOM/SARIF scans."
          action={<Button onClick={() => setOpen(true)}>Connect first repository</Button>}
        />
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {(repos.data ?? []).map((repo) => {
            const safeGhUrl = toSafeGithubUrl(repo.githubUrl);
            return (
              <Panel key={repo.id} className="p-5 flex flex-col justify-between gap-4">
                <div>
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <FolderGit2 className="w-4 h-4 text-[#111111] shrink-0" />
                        <h3 className="text-sm font-semibold text-[#111111] truncate font-mono">
                          {repo.fullName}
                        </h3>
                      </div>
                      <p className="text-xs text-stone-600 mt-1.5 leading-relaxed">
                        {repo.description || 'Connected repository monitored by FORGE.'}
                      </p>
                    </div>
                    <Badge className={statusClasses(repo.indexStatus)}>{repo.indexStatus}</Badge>
                  </div>

                  <div className="grid grid-cols-3 gap-2.5 mt-4">
                    <div className="rounded-lg bg-[#FAFAF8] border border-[#EAEAE4] px-3 py-2">
                      <div className="text-[10px] font-mono uppercase text-stone-500">Files</div>
                      <div className="text-base font-semibold text-[#111111] tabular-nums mt-0.5">
                        {repo.fileCount}
                      </div>
                    </div>
                    <div className="rounded-lg bg-[#FAFAF8] border border-[#EAEAE4] px-3 py-2">
                      <div className="text-[10px] font-mono uppercase text-stone-500">AST Symbols</div>
                      <div className="text-base font-semibold text-[#111111] tabular-nums mt-0.5">
                        {repo.symbolCount}
                      </div>
                    </div>
                    <div className="rounded-lg bg-[#FAFAF8] border border-[#EAEAE4] px-3 py-2">
                      <div className="text-[10px] font-mono uppercase text-stone-500">Dep Edges</div>
                      <div className="text-base font-semibold text-[#111111] tabular-nums mt-0.5">
                        {repo.dependencyCount}
                      </div>
                    </div>
                  </div>
                </div>

                <div className="pt-3.5 border-t border-[#EFEFEA] flex flex-wrap items-center justify-between gap-2">
                  <div className="flex items-center gap-3 text-[11px] font-mono text-stone-500">
                    <span className="flex items-center gap-1">
                      <GitBranch className="w-3 h-3" /> {repo.defaultBranch}
                    </span>
                    <span>{repo.primaryLanguage}</span>
                    <span className="flex items-center gap-1 text-emerald-700">
                      <Webhook className="w-3 h-3" /> HMAC Ready
                    </span>
                    <span>{formatRelativeTime(repo.lastIndexedAt)}</span>
                  </div>

                  <div className="flex items-center gap-1.5">
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() => navigate(`/code-intel?repo=${encodeURIComponent(repo.id)}`)}
                    >
                      <Network className="w-3.5 h-3.5" />
                      AST Graph
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => reindexMutation.mutate(repo.id)}
                      disabled={reindexMutation.isPending}
                      title="Re-run AST indexing"
                      aria-label={`Re-index ${repo.fullName}`}
                    >
                      <RefreshCw className="w-3.5 h-3.5" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => scanMutation.mutate(repo.id)}
                      disabled={scanMutation.isPending}
                      title="Run Secret, SAST, IaC & SCA scan"
                      aria-label={`Scan ${repo.fullName}`}
                    >
                      <Shield className="w-3.5 h-3.5" />
                    </Button>
                    {safeGhUrl && (
                      <a
                        href={safeGhUrl}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="h-8 w-8 inline-flex items-center justify-center rounded-md text-stone-500 hover:text-[#111111] hover:bg-stone-100"
                        title="Open on GitHub"
                        aria-label={`Open ${repo.fullName} on GitHub`}
                      >
                        <ExternalLink className="w-3.5 h-3.5" />
                      </a>
                    )}
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => setPendingDelete(repo)}
                      title="Disconnect repository"
                      aria-label={`Disconnect ${repo.fullName}`}
                      className="text-stone-400 hover:text-red-700"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </Button>
                  </div>
                </div>
              </Panel>
            );
          })}
        </div>
      )}

      {/* Confirmation Dialog before Cascade Delete */}
      <Dialog.Root open={!!pendingDelete} onOpenChange={(isOpen) => !isOpen && setPendingDelete(null)}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-50 bg-black/40 backdrop-blur-[2px]" />
          <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-xl bg-white border border-[#DCDCD5] p-6 shadow-2xl space-y-4">
            <Dialog.Title className="text-sm font-semibold text-[#111111]">
              Disconnect repository?
            </Dialog.Title>
            <p className="text-xs text-stone-600 leading-relaxed">
              This will permanently remove <strong className="font-mono text-[#111111]">{pendingDelete?.fullName}</strong> along with its indexed AST symbols, dependency graph, pull request reviews, security findings, and CI builds in this workspace.
            </p>
            <div className="flex justify-end gap-2 pt-2">
              <Button variant="secondary" onClick={() => setPendingDelete(null)}>
                Cancel
              </Button>
              <Button
                variant="danger"
                disabled={deleteMutation.isPending}
                onClick={() => pendingDelete && deleteMutation.mutate(pendingDelete.id)}
              >
                {deleteMutation.isPending ? 'Disconnecting...' : 'Disconnect Repository'}
              </Button>
            </div>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>

      {/* Connect Repository Modal */}
      <Dialog.Root open={open} onOpenChange={setOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-50 bg-black/35 backdrop-blur-[2px]" />
          <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-full max-w-lg -translate-x-1/2 -translate-y-1/2 rounded-xl bg-white border border-[#DCDCD5] p-6 shadow-2xl">
            <div className="flex items-center justify-between mb-4">
              <Dialog.Title className="text-sm font-semibold text-[#111111]">
                Connect GitHub Repository
              </Dialog.Title>
              <Dialog.Close className="text-stone-400 hover:text-[#111111] cursor-pointer">
                <X className="w-4 h-4" />
              </Dialog.Close>
            </div>

            <form
              onSubmit={handleConnectSubmit}
              className="space-y-4"
            >
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label htmlFor="repo-owner" className="block text-xs font-medium text-stone-700 mb-1">
                    Owner / Org
                  </label>
                  <input
                    id="repo-owner"
                    value={form.owner}
                    onChange={(e) => setForm((f) => ({ ...f, owner: e.target.value }))}
                    className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs font-mono text-[#111111] focus:outline-none focus:border-[#111111]"
                  />
                  {formErrors.owner && (
                    <p className="text-[11px] text-red-600 mt-1">{formErrors.owner}</p>
                  )}
                </div>
                <div>
                  <label htmlFor="repo-name" className="block text-xs font-medium text-stone-700 mb-1">
                    Repository Name
                  </label>
                  <input
                    id="repo-name"
                    value={form.name}
                    onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
                    className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs font-mono text-[#111111] focus:outline-none focus:border-[#111111]"
                  />
                  {formErrors.name && (
                    <p className="text-[11px] text-red-600 mt-1">{formErrors.name}</p>
                  )}
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label htmlFor="repo-branch" className="block text-xs font-medium text-stone-700 mb-1">
                    Default Branch
                  </label>
                  <input
                    id="repo-branch"
                    value={form.defaultBranch}
                    onChange={(e) => setForm((f) => ({ ...f, defaultBranch: e.target.value }))}
                    className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs font-mono text-[#111111] focus:outline-none focus:border-[#111111]"
                  />
                </div>
                <div>
                  <label htmlFor="repo-lang" className="block text-xs font-medium text-stone-700 mb-1">
                    Primary Language
                  </label>
                  <select
                    id="repo-lang"
                    value={form.primaryLanguage}
                    onChange={(e) => setForm((f) => ({ ...f, primaryLanguage: e.target.value }))}
                    className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs text-[#111111] focus:outline-none focus:border-[#111111]"
                  >
                    <option value="Java">Java (Spring Boot)</option>
                    <option value="TypeScript">TypeScript</option>
                    <option value="JavaScript">JavaScript</option>
                  </select>
                </div>
              </div>

              <div>
                <label htmlFor="repo-desc" className="block text-xs font-medium text-stone-700 mb-1">
                  Description
                </label>
                <input
                  id="repo-desc"
                  value={form.description}
                  onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))}
                  className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs text-[#111111] focus:outline-none focus:border-[#111111]"
                />
              </div>

              <label className="flex items-start gap-2.5 p-3 rounded-lg bg-[#FAFAF8] border border-[#E5E5E0] cursor-pointer">
                <input
                  type="checkbox"
                  checked={form.fetchFromGithub}
                  onChange={(e) => setForm((f) => ({ ...f, fetchFromGithub: e.target.checked }))}
                  className="mt-0.5 accent-[#111111]"
                />
                <div>
                  <div className="text-xs font-medium text-[#111111]">
                    Fetch live source files from public GitHub API
                  </div>
                  <div className="text-[11px] text-stone-500 mt-0.5 leading-relaxed">
                    Fetches up to 18 source files via GitHub REST API (Resilience4j circuit-breaker
                    protected). If unchecked or unreachable, provisions a starter Spring codebase.
                  </div>
                </div>
              </label>

              {connectMutation.isError && (
                <div role="alert" className="p-2.5 rounded-md bg-red-50 border border-red-200 text-xs text-red-700">
                  {connectMutation.error instanceof Error
                    ? connectMutation.error.message
                    : 'Failed to connect repository'}
                </div>
              )}

              <div className="flex justify-end gap-2 pt-2">
                <Button type="button" variant="secondary" onClick={() => setOpen(false)}>
                  Cancel
                </Button>
                <Button type="submit" disabled={connectMutation.isPending}>
                  {connectMutation.isPending ? 'Connecting & Indexing...' : 'Connect & Index AST'}
                </Button>
              </div>
            </form>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  );
}
