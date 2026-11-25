import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { FileClock, Search } from 'lucide-react';
import { api } from '../lib/api';
import { Badge, EmptyState, PageHeader, Panel, SkeletonBlock } from '../components/ui';
import { formatRelativeTime } from '../lib/utils';

export function AuditLogsPage() {
  const [filter, setFilter] = useState('');
  const logs = useQuery({
    queryKey: ['audit', filter],
    queryFn: () => api.listAuditLogs(filter || undefined),
  });

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Multi-Tenant Compliance & Traceability"
        title="Audit"
        accentWord="logs."
        description="Immutable tenant-scoped ledger of authentication, repository connections, webhook rejections, finding approvals, security triages, and settings updates."
        actions={
          <div className="flex items-center gap-2 h-9 px-3 rounded-md bg-white border border-[#DCDCD5] focus-within:border-[#111111]">
            <Search className="w-3.5 h-3.5 text-stone-400" />
            <input
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              placeholder="Filter action (e.g. REVIEW, WEBHOOK)..."
              className="bg-transparent text-xs text-[#111111] placeholder:text-stone-400 focus:outline-none w-56"
            />
          </div>
        }
      />

      {logs.isLoading ? (
        <SkeletonBlock className="h-96" />
      ) : (logs.data ?? []).length === 0 ? (
        <EmptyState
          icon={FileClock}
          title="No matching audit entries"
          description="Try clearing the action filter to view all recorded tenant events."
        />
      ) : (
        <Panel className="overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="border-b border-[#EFEFEA] bg-[#FAFAF8] text-[10.5px] font-mono uppercase text-stone-500">
                <tr>
                  <th className="px-5 py-3">Action</th>
                  <th className="px-5 py-3">Actor</th>
                  <th className="px-5 py-3">Resource</th>
                  <th className="px-5 py-3">Details</th>
                  <th className="px-5 py-3 text-right">Time</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[#EFEFEA]">
                {(logs.data ?? []).map((a) => (
                  <tr key={a.id} className="hover:bg-[#FAFAF8]">
                    <td className="px-5 py-3 font-mono font-semibold text-[#111111] whitespace-nowrap">
                      {a.action}
                    </td>
                    <td className="px-5 py-3 whitespace-nowrap">
                      <span className="text-[#111111]">{a.actorEmail}</span>{' '}
                      <Badge className="ml-1 bg-stone-100 text-stone-600 border-stone-200 font-mono text-[10px]">
                        {a.actorRole}
                      </Badge>
                    </td>
                    <td className="px-5 py-3 font-mono text-stone-600 whitespace-nowrap">
                      {a.resourceType}
                    </td>
                    <td className="px-5 py-3 text-stone-600 max-w-md">{a.details}</td>
                    <td className="px-5 py-3 text-right font-mono text-stone-500 whitespace-nowrap">
                      {formatRelativeTime(a.createdAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
      )}
    </div>
  );
}
