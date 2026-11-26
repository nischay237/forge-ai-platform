import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import * as Dialog from '@radix-ui/react-dialog';
import {
  ArrowRight,
  CheckCircle2,
  GitBranch,
  GitPullRequest,
  Globe,
  Plus,
  X,
} from 'lucide-react';
import { api } from '../lib/api';
import { Badge, Button, EmptyState, ErrorBanner, PageHeader, Panel, SkeletonBlock } from '../components/ui';
import { formatRelativeTime, mergeDecisionClasses, severityClasses, statusClasses } from '../lib/utils';

const SAMPLE_DIFF_PRESET = `diff --git a/src/main/java/com/acme/ledger/SettlementService.java b/src/main/java/com/acme/ledger/SettlementService.java
--- a/src/main/java/com/acme/ledger/SettlementService.java
+++ b/src/main/java/com/acme/ledger/SettlementService.java
@@ -20,4 +20,17 @@ public class SettlementService {
     private final LedgerRepository ledgerRepository;
+    private static final SimpleDateFormat PAYOUT_FMT = new SimpleDateFormat("yyyy-MM-dd");
+    private static final String ARCHIVE_AWS_KEY = "AKIAIOSFODNN7EXAMPLE";
+    private static final String LEDGER_DB_PASSWORD = "ProdSettlement#Password99";
+
+    public void reconcilePayouts(List<String> txIds, double fee) {
+        for (String txId : txIds) {
+            var tx = ledgerRepository.findById(txId).get();
+            String sql = "SELECT * FROM settlements WHERE tx_id = '" + txId + "'";
+            try {
+                ledgerRepository.appendDoubleEntry(sql);
+            } catch (Exception e) { }
+        }
+    }
 }`;

export function ReviewsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [open, setOpen] = useState(searchParams.get('new') === '1');
  const [githubOpen, setGithubOpen] = useState(false);
  const [githubPrUrl, setGithubPrUrl] = useState('');
  const [repoId, setRepoId] = useState('');
  const [prNumber, setPrNumber] = useState(225);
  const [title, setTitle] = useState('feat(payouts): add real-time ledger reconciliation worker');
  const [sourceBranch, setSourceBranch] = useState('feat/ledger-reconciliation');
  const [rawDiff, setRawDiff] = useState(SAMPLE_DIFF_PRESET);

  const navigate = useNavigate();
  const qc = useQueryClient();

  const repos = useQuery({ queryKey: ['repos'], queryFn: api.listRepos });
  const reviews = useQuery({ queryKey: ['reviews'], queryFn: () => api.listReviews() });

  useEffect(() => {
    if (searchParams.get('new') === '1') {
      setOpen(true);
      setSearchParams({}, { replace: true });
    }
  }, [searchParams, setSearchParams]);

  useEffect(() => {
    if (!repoId && repos.data?.length) {
      setRepoId(repos.data[0].id);
    }
  }, [repoId, repos.data]);

  const triggerMutation = useMutation({
    mutationFn: api.triggerReview,
    onSuccess: (created) => {
      qc.invalidateQueries({ queryKey: ['reviews'] });
      qc.invalidateQueries({ queryKey: ['overview'] });
      setOpen(false);
      navigate(`/reviews/${created.id}`);
    },
  });

  const importGithubMutation = useMutation({
    mutationFn: api.importGithubPr,
    onSuccess: (created) => {
      qc.invalidateQueries({ queryKey: ['reviews'] });
      qc.invalidateQueries({ queryKey: ['repos'] });
      qc.invalidateQueries({ queryKey: ['overview'] });
      setGithubOpen(false);
      setGithubPrUrl('');
      navigate(`/reviews/${created.id}`);
    },
  });

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Change Impact & Merge Risk Queue"
        title="Pull Request"
        accentWord="reviews."
        description="Import a real GitHub Pull Request or submit a unified diff. FORGE computes AST blast radius, API/migration compatibility, security risks, recommended tests, and an explainable PASS/WARN/BLOCK verdict."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Button onClick={() => setGithubOpen(true)}>
              <Globe className="w-3.5 h-3.5" />
              Import Real GitHub PR
            </Button>
            <Button variant="secondary" onClick={() => setOpen(true)}>
              <Plus className="w-3.5 h-3.5" />
              Analyze Custom Diff
            </Button>
          </div>
        }
      />

      {reviews.isError && (
        <ErrorBanner
          message={reviews.error instanceof Error ? reviews.error.message : 'Could not load pull request reviews'}
          onRetry={() => reviews.refetch()}
        />
      )}

      {reviews.isLoading ? (
        <div className="space-y-3">
          <SkeletonBlock className="h-24" />
          <SkeletonBlock className="h-24" />
        </div>
      ) : (reviews.data ?? []).length === 0 ? (
        <EmptyState
          icon={GitPullRequest}
          title="No pull request reviews yet"
          description="Import a GitHub PR or submit a diff to inspect explainable risk factors, recommended tests, and Monaco side-by-side diffs."
          action={<Button onClick={() => setOpen(true)}>Analyze Pull Request</Button>}
        />
      ) : (
        <Panel className="overflow-hidden divide-y divide-[#EFEFEA]">
          {(reviews.data ?? []).map((r) => (
            <Link
              key={r.id}
              to={`/reviews/${r.id}`}
              className="block p-5 hover:bg-[#FAFAF8] transition-colors"
            >
              <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
                <div className="min-w-0 space-y-1.5">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-mono text-xs font-semibold text-[#111111]">
                      #{r.prNumber}
                    </span>
                    <h3 className="text-sm font-semibold text-[#111111]">{r.title}</h3>
                    {r.mergeDecision && (
                      <Badge className={mergeDecisionClasses(r.mergeDecision)}>{r.mergeDecision}</Badge>
                    )}
                    <Badge className={statusClasses(r.status)}>{r.status}</Badge>
                    {r.publishStatus === 'PUBLISHED_TO_GITHUB' ? (
                      <Badge className="bg-emerald-50 text-emerald-800 border-emerald-200">
                        <CheckCircle2 className="w-3 h-3" /> Published to GitHub
                      </Badge>
                    ) : r.publishedToGithub ? (
                      <Badge className="bg-stone-100 text-stone-700 border-stone-200">
                        <CheckCircle2 className="w-3 h-3" /> Approved Locally
                      </Badge>
                    ) : null}
                  </div>

                  <p className="text-xs text-stone-600 line-clamp-1">{r.summary}</p>

                  <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] font-mono text-stone-500">
                    <span>{r.repositoryName}</span>
                    <span className="flex items-center gap-1">
                      <GitBranch className="w-3 h-3" /> {r.sourceBranch} → {r.targetBranch}
                    </span>
                    <span>{r.author}</span>
                    <span>
                      {r.modelUsed} ({r.promptVersion})
                    </span>
                    <span>{r.processingMs}ms</span>
                    <span>{formatRelativeTime(r.createdAt)}</span>
                  </div>
                </div>

                <div className="flex items-center gap-3 shrink-0">
                  <div className="flex items-center gap-1.5">
                    {r.criticalCount > 0 && (
                      <Badge className={severityClasses('CRITICAL')}>{r.criticalCount} critical</Badge>
                    )}
                    {r.highCount > 0 && (
                      <Badge className={severityClasses('HIGH')}>{r.highCount} high</Badge>
                    )}
                    <Badge className="bg-stone-100 text-stone-700 border-stone-200">
                      {r.findingCount} findings
                    </Badge>
                  </div>

                  <div className="pl-3 border-l border-[#EAEAE4] text-right">
                    <div className="text-[10px] font-mono uppercase text-stone-500">Risk</div>
                    <div
                      className={`text-sm font-mono font-semibold tabular-nums ${
                        r.riskScore >= 65
                          ? 'text-red-700'
                          : r.riskScore > 25
                          ? 'text-amber-700'
                          : 'text-emerald-700'
                      }`}
                    >
                      {r.riskScore}/100
                    </div>
                  </div>

                  <ArrowRight className="w-4 h-4 text-stone-400" />
                </div>
              </div>
            </Link>
          ))}
        </Panel>
      )}

      {/* Import Real GitHub PR Dialog */}
      <Dialog.Root open={githubOpen} onOpenChange={setGithubOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-50 bg-black/35 backdrop-blur-[2px]" />
          <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-full max-w-lg -translate-x-1/2 -translate-y-1/2 rounded-xl bg-white border border-[#DCDCD5] p-6 shadow-2xl">
            <div className="flex items-center justify-between mb-4">
              <Dialog.Title className="text-sm font-semibold text-[#111111] flex items-center gap-2">
                <Globe className="w-4 h-4 text-[#111111]" />
                Import &amp; Scan a Real GitHub Pull Request
              </Dialog.Title>
              <Dialog.Close className="text-stone-400 hover:text-[#111111] cursor-pointer">
                <X className="w-4 h-4" />
              </Dialog.Close>
            </div>

            <form
              onSubmit={(e) => {
                e.preventDefault();
                importGithubMutation.mutate(githubPrUrl);
              }}
              className="space-y-4"
            >
              <p className="text-xs text-stone-600 leading-relaxed">
                Paste any GitHub Pull Request URL. FORGE fetches the real unified diff via GitHub API,
                runs AST impact, API/migration compatibility, and security analysis, and requires human
                sign-off before publishing comments.
              </p>

              <div>
                <label htmlFor="gh-pr-url" className="block text-xs font-medium text-stone-700 mb-1.5">
                  GitHub Pull Request URL
                </label>
                <input
                  id="gh-pr-url"
                  required
                  value={githubPrUrl}
                  onChange={(e) => setGithubPrUrl(e.target.value)}
                  placeholder="https://github.com/owner/repo/pull/1"
                  className="w-full h-10 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs font-mono text-[#111111] focus:outline-none focus:border-[#111111]"
                />
              </div>

              {importGithubMutation.isError && (
                <div role="alert" className="p-2.5 rounded-md bg-red-50 border border-red-200 text-xs text-red-700">
                  {importGithubMutation.error instanceof Error
                    ? importGithubMutation.error.message
                    : 'Could not import PR from GitHub'}
                </div>
              )}

              <div className="flex justify-end gap-2 pt-1">
                <Button type="button" variant="secondary" onClick={() => setGithubOpen(false)}>
                  Cancel
                </Button>
                <Button type="submit" disabled={importGithubMutation.isPending}>
                  {importGithubMutation.isPending ? 'Fetching PR from GitHub...' : 'Fetch & Scan Real PR'}
                </Button>
              </div>
            </form>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>

      {/* Trigger New PR Review Dialog */}
      <Dialog.Root open={open} onOpenChange={setOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-50 bg-black/35 backdrop-blur-[2px]" />
          <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-full max-w-2xl -translate-x-1/2 -translate-y-1/2 rounded-xl bg-white border border-[#DCDCD5] p-6 shadow-2xl max-h-[90vh] overflow-y-auto">
            <div className="flex items-center justify-between mb-4">
              <Dialog.Title className="text-sm font-semibold text-[#111111] flex items-center gap-2">
                <GitPullRequest className="w-4 h-4 text-[#111111]" />
                Enqueue Pull Request Review (Transactional Outbox + SSE)
              </Dialog.Title>
              <Dialog.Close className="text-stone-400 hover:text-[#111111] cursor-pointer">
                <X className="w-4 h-4" />
              </Dialog.Close>
            </div>

            <form
              onSubmit={(e) => {
                e.preventDefault();
                triggerMutation.mutate({
                  repositoryId: repoId,
                  prNumber,
                  title,
                  sourceBranch,
                  targetBranch: 'main',
                  rawDiff,
                });
              }}
              className="space-y-4"
            >
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <div className="sm:col-span-2">
                  <label htmlFor="pr-repo" className="block text-xs font-medium text-stone-700 mb-1">Repository</label>
                  <select
                    id="pr-repo"
                    required
                    value={repoId}
                    onChange={(e) => setRepoId(e.target.value)}
                    className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs font-mono text-[#111111] focus:outline-none focus:border-[#111111]"
                  >
                    {(repos.data ?? []).map((r) => (
                      <option key={r.id} value={r.id}>
                        {r.fullName}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label htmlFor="pr-num" className="block text-xs font-medium text-stone-700 mb-1">PR Number</label>
                  <input
                    id="pr-num"
                    required
                    type="number"
                    value={prNumber}
                    onChange={(e) => setPrNumber(Number(e.target.value))}
                    className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs font-mono text-[#111111] focus:outline-none focus:border-[#111111]"
                  />
                </div>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label htmlFor="pr-title" className="block text-xs font-medium text-stone-700 mb-1">PR Title</label>
                  <input
                    id="pr-title"
                    required
                    value={title}
                    onChange={(e) => setTitle(e.target.value)}
                    className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs text-[#111111] focus:outline-none focus:border-[#111111]"
                  />
                </div>
                <div>
                  <label htmlFor="pr-branch" className="block text-xs font-medium text-stone-700 mb-1">
                    Source Branch
                  </label>
                  <input
                    id="pr-branch"
                    required
                    value={sourceBranch}
                    onChange={(e) => setSourceBranch(e.target.value)}
                    className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs font-mono text-[#111111] focus:outline-none focus:border-[#111111]"
                  />
                </div>
              </div>

              <div>
                <label htmlFor="pr-diff" className="block text-xs font-medium text-stone-700 mb-1">
                  Unified Git Diff (Java, TypeScript, OpenAPI YAML, SQL migration, or Dockerfile)
                </label>
                <textarea
                  id="pr-diff"
                  required
                  rows={10}
                  value={rawDiff}
                  onChange={(e) => setRawDiff(e.target.value)}
                  className="w-full p-3 rounded-lg bg-[#141416] border border-[#27272A] text-xs font-mono text-stone-200 focus:outline-none focus:border-[#111111]"
                />
              </div>

              {triggerMutation.isError && (
                <div role="alert" className="p-2.5 rounded-md bg-red-50 border border-red-200 text-xs text-red-700">
                  {triggerMutation.error instanceof Error
                    ? triggerMutation.error.message
                    : 'Failed to enqueue review'}
                </div>
              )}

              <div className="flex justify-end gap-2">
                <Button type="button" variant="secondary" onClick={() => setOpen(false)}>
                  Cancel
                </Button>
                <Button type="submit" disabled={triggerMutation.isPending || !repoId}>
                  {triggerMutation.isPending ? 'Enqueueing...' : 'Run Live Review Pipeline'}
                </Button>
              </div>
            </form>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  );
}
