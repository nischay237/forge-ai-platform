import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import * as Dialog from '@radix-ui/react-dialog';
import {
  ArrowUpRight,
  Ban,
  CheckCircle2,
  Download,
  FileCode2,
  ShieldAlert,
  ShieldCheck,
  X,
} from 'lucide-react';
import { api } from '../lib/api';
import { useForgeStore } from '../lib/store';
import type { SecurityFinding } from '../lib/types';
import { Badge, Button, EmptyState, ErrorBanner, PageHeader, Panel, SkeletonBlock } from '../components/ui';
import { downloadJsonFile, formatRelativeTime, severityClasses, statusClasses } from '../lib/utils';

export function SecurityCenterPage() {
  const [severityFilter, setSeverityFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [exportRepoId, setExportRepoId] = useState('');
  const [suppressTarget, setSuppressTarget] = useState<SecurityFinding | null>(null);
  const [suppressReason, setSuppressReason] = useState('');
  const [actionError, setActionError] = useState<string | null>(null);
  const user = useForgeStore((s) => s.user);
  const isAdmin = user?.role === 'OWNER' || user?.role === 'ADMIN';
  const qc = useQueryClient();

  const repos = useQuery({ queryKey: ['repos'], queryFn: api.listRepos });
  const activeExportRepo = exportRepoId || repos.data?.[0]?.id || '';

  // Unfiltered query keeps KPI summary accurate regardless of active table filters
  const allFindings = useQuery({
    queryKey: ['security', 'all'],
    queryFn: () => api.listSecurityFindings(),
  });

  const findings = useQuery({
    queryKey: ['security', severityFilter, statusFilter],
    queryFn: () =>
      api.listSecurityFindings({
        severity: severityFilter || undefined,
        status: statusFilter || undefined,
      }),
  });

  const triageMutation = useMutation({
    mutationFn: ({
      id,
      status,
    }: {
      id: string;
      status: 'OPEN' | 'MITIGATED' | 'FALSE_POSITIVE' | 'SUPPRESSED';
    }) => api.triageSecurityFinding(id, status),
    onSuccess: () => {
      setActionError(null);
      qc.invalidateQueries({ queryKey: ['security'] });
      qc.invalidateQueries({ queryKey: ['overview'] });
      qc.invalidateQueries({ queryKey: ['audit'] });
    },
    onError: (err: unknown) => {
      setActionError(err instanceof Error ? err.message : 'Failed to triage security finding');
    },
  });

  const suppressMutation = useMutation({
    mutationFn: api.createSuppression,
    onSuccess: () => {
      setSuppressTarget(null);
      setSuppressReason('');
      setActionError(null);
      qc.invalidateQueries({ queryKey: ['security'] });
      qc.invalidateQueries({ queryKey: ['overview'] });
      qc.invalidateQueries({ queryKey: ['audit'] });
    },
    onError: (err: unknown) => {
      setActionError(err instanceof Error ? err.message : 'Failed to create suppression');
    },
  });

  const handleExport = async (kind: 'sarif' | 'sbom') => {
    if (!activeExportRepo) return;
    setActionError(null);
    try {
      const repoName = (repos.data ?? []).find((r) => r.id === activeExportRepo)?.name ?? 'repository';
      const payload =
        kind === 'sarif'
          ? await api.exportSarif(activeExportRepo)
          : await api.exportSbom(activeExportRepo);
      downloadJsonFile(
        kind === 'sarif' ? `${repoName}.sarif.json` : `${repoName}.cyclonedx-1.5.sbom.json`,
        payload
      );
    } catch (err: unknown) {
      setActionError(err instanceof Error ? err.message : `Failed to export ${kind.toUpperCase()}`);
    }
  };

  const summaryItems = allFindings.data ?? [];
  const items = findings.data ?? [];
  const criticalCount = summaryItems.filter((i) => i.severity === 'CRITICAL' && i.status === 'OPEN').length;
  const highCount = summaryItems.filter((i) => i.severity === 'HIGH' && i.status === 'OPEN').length;
  const mitigatedCount = summaryItems.filter((i) => i.status === 'MITIGATED').length;
  const suppressedCount = summaryItems.filter((i) => i.status === 'SUPPRESSED').length;

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Secrets · Shannon Entropy · SAST · IaC · SCA · CycloneDX SBOM · SARIF 2.1.0"
        title="Security"
        accentWord="center."
        description="Centralized AppSec & supply-chain vulnerability triage with persistent fingerprint suppressions, CycloneDX 1.5 SBOM generation, and OASIS SARIF 2.1.0 exports."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            {(repos.data ?? []).length > 0 && (
              <>
                <select
                  aria-label="Export repository"
                  value={activeExportRepo}
                  onChange={(e) => setExportRepoId(e.target.value)}
                  className="h-9 px-2.5 rounded-md bg-white border border-[#DCDCD5] text-xs font-mono text-[#111111]"
                >
                  {(repos.data ?? []).map((r) => (
                    <option key={r.id} value={r.id}>
                      {r.name}
                    </option>
                  ))}
                </select>
                <Button variant="secondary" size="sm" onClick={() => handleExport('sarif')}>
                  <Download className="w-3.5 h-3.5" />
                  SARIF 2.1.0
                </Button>
                <Button variant="secondary" size="sm" onClick={() => handleExport('sbom')}>
                  <FileCode2 className="w-3.5 h-3.5" />
                  CycloneDX SBOM
                </Button>
              </>
            )}
          </div>
        }
      />

      {actionError && <ErrorBanner message={actionError} />}
      {findings.isError && (
        <ErrorBanner
          message={findings.error instanceof Error ? findings.error.message : 'Failed to load security findings'}
          onRetry={() => findings.refetch()}
        />
      )}

      <Panel className="overflow-hidden">
        <div className="grid grid-cols-2 sm:grid-cols-4 divide-y sm:divide-y-0 sm:divide-x divide-[#EAEAE4]">
          <div className="p-4 sm:px-5">
            <div className="text-[11px] font-mono uppercase tracking-[0.08em] text-stone-500">
              Open Critical
            </div>
            <div className="text-2xl font-semibold text-red-700 tabular-nums mt-1">
              {criticalCount}
            </div>
          </div>
          <div className="p-4 sm:px-5">
            <div className="text-[11px] font-mono uppercase tracking-[0.08em] text-stone-500">
              Open High
            </div>
            <div className="text-2xl font-semibold text-amber-700 tabular-nums mt-1">
              {highCount}
            </div>
          </div>
          <div className="p-4 sm:px-5">
            <div className="text-[11px] font-mono uppercase tracking-[0.08em] text-stone-500">
              Mitigated
            </div>
            <div className="text-2xl font-semibold text-emerald-700 tabular-nums mt-1">
              {mitigatedCount}
            </div>
          </div>
          <div className="p-4 sm:px-5">
            <div className="text-[11px] font-mono uppercase tracking-[0.08em] text-stone-500">
              Suppressed
            </div>
            <div className="text-2xl font-semibold text-stone-600 tabular-nums mt-1">
              {suppressedCount}
            </div>
          </div>
        </div>
      </Panel>

      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-xs font-mono text-stone-500">
          Showing {items.length} of {summaryItems.length} tracked security findings
        </span>
        <div className="flex items-center gap-2">
          <select
            aria-label="Filter by severity"
            value={severityFilter}
            onChange={(e) => setSeverityFilter(e.target.value)}
            className="h-9 px-3 rounded-md bg-white border border-[#DCDCD5] text-xs text-[#111111] focus:outline-none focus:border-[#111111]"
          >
            <option value="">All Severities</option>
            <option value="CRITICAL">Critical</option>
            <option value="HIGH">High</option>
            <option value="MEDIUM">Medium</option>
            <option value="LOW">Low</option>
          </select>
          <select
            aria-label="Filter by status"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="h-9 px-3 rounded-md bg-white border border-[#DCDCD5] text-xs text-[#111111] focus:outline-none focus:border-[#111111]"
          >
            <option value="">All Statuses</option>
            <option value="OPEN">Open</option>
            <option value="MITIGATED">Mitigated</option>
            <option value="FALSE_POSITIVE">False Positive</option>
            <option value="SUPPRESSED">Suppressed</option>
          </select>
        </div>
      </div>

      {findings.isLoading ? (
        <div className="space-y-3">
          <SkeletonBlock className="h-28" />
          <SkeletonBlock className="h-28" />
        </div>
      ) : items.length === 0 ? (
        <EmptyState
          icon={ShieldCheck}
          title="No security findings match your filters"
          description="Your repositories are clean for the selected filter criteria."
        />
      ) : (
        <div className="space-y-3">
          {items.map((f) => (
            <Panel key={f.id} className="p-5 space-y-3">
              <div className="flex flex-col md:flex-row md:items-start justify-between gap-3">
                <div className="space-y-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge className={severityClasses(f.severity)}>{f.severity}</Badge>
                    <Badge className="bg-stone-100 text-stone-700 border-stone-200 font-mono">
                      {f.scanner}
                    </Badge>
                    <span className="font-mono text-[11px] text-stone-600">{f.ruleId}</span>
                    {f.cweId && (
                      <span className="font-mono text-[11px] font-medium text-[#111111]">
                        {f.cweId}
                      </span>
                    )}
                    <Badge className={statusClasses(f.status)}>{f.status}</Badge>
                  </div>
                  <h3 className="text-sm font-semibold text-[#111111] flex items-center gap-1.5 pt-1">
                    <ShieldAlert className="w-4 h-4 text-red-600 shrink-0" />
                    {f.title}
                  </h3>
                  <div className="flex flex-wrap items-center gap-2 text-[11px] font-mono text-stone-500">
                    <span>
                      {f.filePath}:{f.lineNumber} · detected {formatRelativeTime(f.detectedAt)}
                    </span>
                    {f.reviewId && (
                      <Link
                        to={`/reviews/${f.reviewId}`}
                        className="text-[#111111] hover:underline inline-flex items-center gap-0.5"
                      >
                        Source PR <ArrowUpRight className="w-3 h-3" />
                      </Link>
                    )}
                  </div>
                </div>

                <div className="flex flex-wrap items-center gap-1.5 shrink-0">
                  {f.status !== 'MITIGATED' && (
                    <Button
                      size="sm"
                      variant="secondary"
                      onClick={() => triageMutation.mutate({ id: f.id, status: 'MITIGATED' })}
                      disabled={triageMutation.isPending}
                    >
                      <CheckCircle2 className="w-3.5 h-3.5 text-emerald-700" />
                      Mitigated
                    </Button>
                  )}
                  {isAdmin && f.fingerprint && f.status !== 'SUPPRESSED' && (
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => setSuppressTarget(f)}
                    >
                      <Ban className="w-3.5 h-3.5" />
                      Suppress
                    </Button>
                  )}
                  {f.status !== 'FALSE_POSITIVE' && (
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => triageMutation.mutate({ id: f.id, status: 'FALSE_POSITIVE' })}
                      disabled={triageMutation.isPending}
                    >
                      False Positive
                    </Button>
                  )}
                  {f.status !== 'OPEN' && (
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => triageMutation.mutate({ id: f.id, status: 'OPEN' })}
                      disabled={triageMutation.isPending}
                    >
                      Reopen
                    </Button>
                  )}
                </div>
              </div>

              {f.maskedEvidence && (
                <div className="rounded-lg bg-red-50/65 border border-red-200/80 px-3 py-2 font-mono text-[11px] text-red-900 overflow-x-auto">
                  {f.maskedEvidence}
                </div>
              )}

              <div className="text-xs text-stone-600 leading-relaxed">
                <span className="text-[#111111] font-semibold">Remediation: </span>
                {f.remediation}
              </div>
            </Panel>
          ))}
        </div>
      )}

      {/* Persistent Finding Suppression Modal */}
      <Dialog.Root open={!!suppressTarget} onOpenChange={(o) => !o && setSuppressTarget(null)}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-50 bg-black/40 backdrop-blur-[2px]" />
          <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-xl bg-white border border-[#DCDCD5] p-6 shadow-2xl space-y-4">
            <div className="flex items-center justify-between">
              <Dialog.Title className="text-sm font-semibold text-[#111111]">
                Suppress Finding by Fingerprint
              </Dialog.Title>
              <Dialog.Close className="text-stone-400 hover:text-[#111111] cursor-pointer">
                <X className="w-4 h-4" />
              </Dialog.Close>
            </div>
            <p className="text-xs text-stone-600">
              Record an audited suppression for <strong className="font-mono">{suppressTarget?.ruleId}</strong> so matching future scans inherit this decision.
            </p>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                if (!suppressTarget?.fingerprint) return;
                suppressMutation.mutate({
                  repositoryId: suppressTarget.repositoryId,
                  fingerprint: suppressTarget.fingerprint,
                  ruleId: suppressTarget.ruleId,
                  reason: suppressReason,
                });
              }}
              className="space-y-3"
            >
              <div>
                <label htmlFor="suppress-reason" className="block text-xs font-medium text-stone-700 mb-1">
                  Justification (audited)
                </label>
                <textarea
                  id="suppress-reason"
                  required
                  minLength={5}
                  rows={3}
                  value={suppressReason}
                  onChange={(e) => setSuppressReason(e.target.value)}
                  placeholder="Explain compensating controls or accepted risk rationale..."
                  className="w-full p-2.5 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs text-[#111111] focus:outline-none focus:border-[#111111]"
                />
              </div>
              <div className="flex justify-end gap-2">
                <Button type="button" variant="secondary" onClick={() => setSuppressTarget(null)}>
                  Cancel
                </Button>
                <Button type="submit" disabled={suppressMutation.isPending}>
                  {suppressMutation.isPending ? 'Saving...' : 'Confirm Suppression'}
                </Button>
              </div>
            </form>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  );
}
