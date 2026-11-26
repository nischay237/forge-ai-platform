import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { FileClock, Search } from 'lucide-react';
import { api } from '../lib/api';
import { Badge, EmptyState, ErrorBanner, PageHeader, PaginationBar, Panel, SkeletonBlock } from '../components/ui';
import { formatRelativeTime } from '../lib/utils';

export function AuditLogsPage() {
  const [filter, setFilter] = useState('');
  const [page, setPage] = useState(1);
  const logs = useQuery({
    queryKey: ['audit', filter],
    queryFn: () => api.listAuditLogs(filter || undefined),
  });

  const PAGE_SIZE = 15;
  const allRows = logs.data ?? [];
  const totalPages = Math.max(1, Math.ceil(allRows.length / PAGE_SIZE));
  const currentPage = Math.min(page, totalPages);
  const pagedRows = allRows.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE);

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Multi-Tenant Compliance & Traceability"
        title="Audit"
        accentWord="logs."
        description="Immutable tenant-scoped ledger of authentication, lockouts, webhook rejections, finding approvals, suppressions, policy updates, and settings changes."
        actions={
          <div className="flex items-center gap-2 h-9 px-3 rounded-md bg-white border border-[#DCDCD5] focus-within:border-[#111111]">
            <Search className="w-3.5 h-3.5 text-stone-400" />
            <input
              aria-label="Filter audit logs by action"
              value={filter}
              onChange={(e) => {
                setFilter(e.target.value);
                setPage(1);
              }}
              placeholder="Filter action (e.g. REVIEW, WEBHOOK, POLICY)..."
              className="bg-transparent text-xs text-[#111111] placeholder:text-stone-400 focus:outline-none w-56"
            />
          </div>
        }
      />

      {logs.isError && (
        <ErrorBanner
          message={logs.error instanceof Error ? logs.error.message : 'Could not load audit logs'}
          onRetry={() => logs.refetch()}
        />
      )}

      {logs.isLoading ? (
        <SkeletonBlock className="h-96" />
      ) : (logs.data ?? []).length === 0 && !logs.isError ? (
        <EmptyState
          icon={FileClock}
          title="No matching audit entries"
          description="Try clearing the action filter to view all recorded tenant events."
        />
      ) : !logs.isError ? (
        <div className="space-y-3">
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
                  {pagedRows.map((a) => (
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
          <PaginationBar
            page={currentPage}
            pageSize={PAGE_SIZE}
            totalItems={allRows.length}
            onPageChange={setPage}
          />
        </div>
      ) : null}
    </div>
  );
}
