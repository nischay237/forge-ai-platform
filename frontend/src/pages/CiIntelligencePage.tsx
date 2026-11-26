import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import * as Dialog from '@radix-ui/react-dialog';
import { Activity, AlertTriangle, CheckCircle2, Plus, Terminal, X } from 'lucide-react';
import { api } from '../lib/api';
import { Badge, Button, EmptyState, ErrorBanner, PageHeader, Panel, SkeletonBlock } from '../components/ui';
import { formatRelativeTime, statusClasses } from '../lib/utils';

const SAMPLE_CI_LOG = `[INFO] Running com.acme.ledger.SettlementIntegrationTest
[ERROR] java.lang.OutOfMemoryError: Java heap space
    at java.base/java.util.Arrays.copyOf(Arrays.java:3537)
    at com.acme.ledger.BatchExporter.loadLedgerWindow(BatchExporter.java:91)
[ERROR] Process terminated with exit code 137`;

export function CiIntelligencePage() {
  const [selectedBuildId, setSelectedBuildId] = useState<string>('');
  const [open, setOpen] = useState(false);
  const [repoId, setRepoId] = useState('');
  const [pipelineName, setPipelineName] = useState('backend-ci / surefire-integration');
  const [branch, setBranch] = useState('feat/batch-export-streaming');
  const [rawLog, setRawLog] = useState(SAMPLE_CI_LOG);

  const qc = useQueryClient();
  const repos = useQuery({ queryKey: ['repos'], queryFn: api.listRepos });
  const builds = useQuery({ queryKey: ['ci'], queryFn: () => api.listCiBuilds() });

  useEffect(() => {
    if (!selectedBuildId && builds.data?.length) {
      const firstFailed = builds.data.find((b) => b.status === 'FAILED');
      setSelectedBuildId((firstFailed ?? builds.data[0]).id);
    }
  }, [builds.data, selectedBuildId]);

  useEffect(() => {
    if (!repoId && repos.data?.length) {
      setRepoId(repos.data[0].id);
    }
  }, [repos.data, repoId]);

  const submitMutation = useMutation({
    mutationFn: api.submitCiBuild,
    onSuccess: (created) => {
      qc.invalidateQueries({ queryKey: ['ci'] });
      qc.invalidateQueries({ queryKey: ['overview'] });
      setSelectedBuildId(created.id);
      setOpen(false);
    },
  });

  const active = (builds.data ?? []).find((b) => b.id === selectedBuildId) ?? builds.data?.[0];

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Build Failure Diagnosis & Remediation"
        title="CI"
        accentWord="intelligence."
        description="Inspect CI build logs, classify root causes (test regressions, Flyway schema drift, OOM/SIGKILL, compiler breaks, security gates), and generate remediation playbooks."
        actions={
          <Button onClick={() => setOpen(true)}>
            <Plus className="w-3.5 h-3.5" />
            Diagnose CI Log
          </Button>
        }
      />

      {builds.isError && (
        <ErrorBanner
          message={builds.error instanceof Error ? builds.error.message : 'Could not load CI pipeline builds'}
          onRetry={() => builds.refetch()}
        />
      )}

      {builds.isLoading ? (
        <SkeletonBlock className="h-[480px]" />
      ) : (builds.data ?? []).length === 0 ? (
        <EmptyState
          icon={Activity}
          title="No CI pipeline runs recorded"
          description="Submit a failed or passing CI log to test automated root-cause classification."
          action={<Button onClick={() => setOpen(true)}>Diagnose first CI log</Button>}
        />
      ) : (
        <div className="grid grid-cols-1 xl:grid-cols-12 gap-5">
          <Panel className="xl:col-span-4 overflow-hidden">
            <div className="px-4 py-3 border-b border-[#EFEFEA] bg-[#FAFAF8] text-xs font-semibold text-[#111111]">
              Pipeline History ({builds.data?.length ?? 0})
            </div>
            <div className="divide-y divide-[#EFEFEA] max-h-[580px] overflow-y-auto">
              {(builds.data ?? []).map((b) => {
                const isSelected = active?.id === b.id;
                return (
                  <button
                    key={b.id}
                    onClick={() => setSelectedBuildId(b.id)}
                    className={`w-full text-left p-4 transition-colors cursor-pointer border-l-2 ${
                      isSelected
                        ? 'bg-[#FAFAF8] border-l-[#111111]'
                        : 'hover:bg-[#FAFAF8]/60 border-l-transparent'
                    }`}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-xs font-semibold text-[#111111] truncate">
                        {b.pipelineName}
                      </span>
                      <Badge className={statusClasses(b.status)}>{b.status}</Badge>
                    </div>
                    <div className="text-[11px] font-mono text-stone-500 mt-1 truncate">
                      {b.repositoryName} · {b.branch}
                    </div>
                    <div className="flex items-center justify-between text-[10px] font-mono text-stone-500 mt-1.5">
                      <span>
                        {b.failureCategory && b.failureCategory !== 'NONE'
                          ? b.failureCategory
                          : 'HEALTHY'}
                      </span>
                      <span>
                        {b.durationSeconds}s · {formatRelativeTime(b.triggeredAt)}
                      </span>
                    </div>
                  </button>
                );
              })}
            </div>
          </Panel>

          {active && (
            <div className="xl:col-span-8 space-y-4">
              <Panel className="p-5 space-y-4">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <div className="flex items-center gap-2">
                      {active.status === 'FAILED' ? (
                        <AlertTriangle className="w-4 h-4 text-red-600" />
                      ) : (
                        <CheckCircle2 className="w-4 h-4 text-emerald-700" />
                      )}
                      <h2 className="text-sm font-semibold text-[#111111]">{active.pipelineName}</h2>
                      <Badge className={statusClasses(active.status)}>{active.status}</Badge>
                    </div>
                    <div className="text-[11px] font-mono text-stone-500 mt-1">
                      {active.repositoryName} · branch:{active.branch} · sha:
                      {active.commitSha.slice(0, 8)} · {active.durationSeconds}s
                    </div>
                  </div>
                  {active.failureCategory && active.failureCategory !== 'NONE' && (
                    <Badge className="bg-amber-50 text-amber-800 border-amber-200 font-mono">
                      {active.failureCategory}
                    </Badge>
                  )}
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div className="p-3.5 rounded-lg bg-[#FAFAF8] border border-[#EAEAE4]">
                    <div className="text-[10px] font-mono uppercase tracking-[0.12em] text-stone-500 mb-1.5">
                      Root Cause Diagnosis
                    </div>
                    <p className="text-xs text-[#111111] leading-relaxed">
                      {active.rootCauseSummary}
                    </p>
                  </div>
                  <div className="p-3.5 rounded-lg bg-emerald-50/70 border border-emerald-200">
                    <div className="text-[10px] font-mono uppercase tracking-[0.12em] text-emerald-800 mb-1.5">
                      Recommended Remediation
                    </div>
                    <pre className="text-xs text-emerald-950 whitespace-pre-wrap font-sans leading-relaxed">
                      {active.remediationSteps}
                    </pre>
                  </div>
                </div>
              </Panel>

              <Panel className="overflow-hidden border-[#27272A]">
                <div className="px-4 py-2.5 bg-[#1B1B1F] border-b border-white/10 flex items-center justify-between text-xs font-mono text-stone-300">
                  <span className="flex items-center gap-2">
                    <Terminal className="w-3.5 h-3.5 text-stone-400" />
                    Build Output Log
                  </span>
                  <span className="text-[10px] text-stone-500">stdout / stderr</span>
                </div>
                <div className="p-4 bg-[#141416] font-mono text-[11.5px] leading-relaxed overflow-x-auto max-h-[340px] overflow-y-auto space-y-0.5">
                  {active.rawLog.split(/\r?\n/).map((line, i) => {
                    const isErr = /error|exception|fail|mismatch|outofmemory|137/i.test(line);
                    return (
                      <div
                        key={i}
                        className={`flex gap-3 px-2 py-0.5 rounded ${
                          isErr ? 'bg-red-500/20 text-red-200' : 'text-stone-300'
                        }`}
                      >
                        <span className="text-stone-600 select-none w-6 text-right shrink-0 tabular-nums">
                          {i + 1}
                        </span>
                        <span className="whitespace-pre">{line}</span>
                      </div>
                    );
                  })}
                </div>
              </Panel>
            </div>
          )}
        </div>
      )}

      <Dialog.Root open={open} onOpenChange={setOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-50 bg-black/35 backdrop-blur-[2px]" />
          <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-full max-w-xl -translate-x-1/2 -translate-y-1/2 rounded-xl bg-white border border-[#DCDCD5] p-6 shadow-2xl">
            <div className="flex items-center justify-between mb-4">
              <Dialog.Title className="text-sm font-semibold text-[#111111]">
                Submit Failed CI Log for Root-Cause Diagnosis
              </Dialog.Title>
              <Dialog.Close className="text-stone-400 hover:text-[#111111] cursor-pointer">
                <X className="w-4 h-4" />
              </Dialog.Close>
            </div>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                submitMutation.mutate({
                  repositoryId: repoId,
                  pipelineName,
                  branch,
                  status: 'FAILED',
                  rawLog,
                });
              }}
              className="space-y-4"
            >
              <div>
                <label htmlFor="ci-repo" className="block text-xs font-medium text-stone-700 mb-1">Repository</label>
                <select
                  id="ci-repo"
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
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label htmlFor="ci-pipe" className="block text-xs font-medium text-stone-700 mb-1">
                    Pipeline Name
                  </label>
                  <input
                    id="ci-pipe"
                    required
                    value={pipelineName}
                    onChange={(e) => setPipelineName(e.target.value)}
                    className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs font-mono text-[#111111] focus:outline-none focus:border-[#111111]"
                  />
                </div>
                <div>
                  <label htmlFor="ci-branch" className="block text-xs font-medium text-stone-700 mb-1">Branch</label>
                  <input
                    id="ci-branch"
                    required
                    value={branch}
                    onChange={(e) => setBranch(e.target.value)}
                    className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs font-mono text-[#111111] focus:outline-none focus:border-[#111111]"
                  />
                </div>
              </div>
              <div>
                <label htmlFor="ci-log" className="block text-xs font-medium text-stone-700 mb-1">
                  CI Log Output
                </label>
                <textarea
                  id="ci-log"
                  required
                  rows={8}
                  value={rawLog}
                  onChange={(e) => setRawLog(e.target.value)}
                  className="w-full p-3 rounded-lg bg-[#141416] border border-[#27272A] text-xs font-mono text-stone-200 focus:outline-none focus:border-[#111111]"
                />
              </div>

              {submitMutation.isError && (
                <div role="alert" className="p-2.5 rounded-md bg-red-50 border border-red-200 text-xs text-red-700">
                  {submitMutation.error instanceof Error
                    ? submitMutation.error.message
                    : 'Failed to submit CI log'}
                </div>
              )}

              <div className="flex justify-end gap-2">
                <Button type="button" variant="secondary" onClick={() => setOpen(false)}>
                  Cancel
                </Button>
                <Button type="submit" disabled={submitMutation.isPending || !repoId}>
                  {submitMutation.isPending ? 'Analyzing...' : 'Analyze Failure Log'}
                </Button>
              </div>
            </form>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  );
}
