import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, ShieldAlert, ShieldCheck } from 'lucide-react';
import { api } from '../lib/api';
import { Badge, Button, EmptyState, PageHeader, Panel, SkeletonBlock } from '../components/ui';
import { formatRelativeTime, severityClasses, statusClasses } from '../lib/utils';

export function SecurityCenterPage() {
  const [severityFilter, setSeverityFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const qc = useQueryClient();

  const findings = useQuery({
    queryKey: ['security', severityFilter, statusFilter],
    queryFn: () =>
      api.listSecurityFindings({
        severity: severityFilter || undefined,
        status: statusFilter || undefined,
      }),
  });

  const triageMutation = useMutation({
    mutationFn: ({ id, status }: { id: string; status: 'OPEN' | 'MITIGATED' | 'FALSE_POSITIVE' }) =>
      api.triageSecurityFinding(id, status),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['security'] });
      qc.invalidateQueries({ queryKey: ['overview'] });
      qc.invalidateQueries({ queryKey: ['audit'] });
    },
  });

  const items = findings.data ?? [];
  const criticalCount = items.filter((i) => i.severity === 'CRITICAL' && i.status === 'OPEN').length;
  const highCount = items.filter((i) => i.severity === 'HIGH' && i.status === 'OPEN').length;
  const mitigatedCount = items.filter((i) => i.status === 'MITIGATED').length;

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Gitleaks Secrets · Semgrep SAST · Prompt-Injection Defense"
        title="Security"
        accentWord="center."
        description="Centralized vulnerability and secret-leakage triage across all tenant repositories. Secrets are strictly masked before persistence and UI serialization."
        actions={
          <div className="flex items-center gap-2">
            <select
              value={severityFilter}
              onChange={(e) => setSeverityFilter(e.target.value)}
              className="h-9 px-3 rounded-md bg-white border border-[#DCDCD5] text-xs text-[#111111] focus:outline-none focus:border-[#111111]"
            >
              <option value="">All Severities</option>
              <option value="CRITICAL">Critical</option>
              <option value="HIGH">High</option>
              <option value="MEDIUM">Medium</option>
            </select>
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="h-9 px-3 rounded-md bg-white border border-[#DCDCD5] text-xs text-[#111111] focus:outline-none focus:border-[#111111]"
            >
              <option value="">All Statuses</option>
              <option value="OPEN">Open</option>
              <option value="MITIGATED">Mitigated</option>
              <option value="FALSE_POSITIVE">False Positive</option>
            </select>
          </div>
        }
      />

      <Panel className="overflow-hidden">
        <div className="grid grid-cols-1 sm:grid-cols-3 divide-y sm:divide-y-0 sm:divide-x divide-[#EAEAE4]">
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
        </div>
      </Panel>

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
                  <div className="text-[11px] font-mono text-stone-500">
                    {f.filePath}:{f.lineNumber} · detected {formatRelativeTime(f.detectedAt)}
                  </div>
                </div>

                <div className="flex items-center gap-1.5 shrink-0">
                  {f.status !== 'MITIGATED' && (
                    <Button
                      size="sm"
                      variant="secondary"
                      onClick={() => triageMutation.mutate({ id: f.id, status: 'MITIGATED' })}
                      disabled={triageMutation.isPending}
                    >
                      <CheckCircle2 className="w-3.5 h-3.5 text-emerald-700" />
                      Mark Mitigated
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
    </div>
  );
}
