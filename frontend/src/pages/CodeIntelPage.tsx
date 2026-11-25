import React, { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import * as Tabs from '@radix-ui/react-tabs';
import Editor from '@monaco-editor/react';
import {
  Background,
  Controls,
  MarkerType,
  ReactFlow,
  type Edge,
  type Node,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { FileCode2, GitFork, Radar, Search } from 'lucide-react';
import { api } from '../lib/api';
import { Badge, Button, EmptyState, PageHeader, Panel, SkeletonBlock } from '../components/ui';
import { severityClasses } from '../lib/utils';

const LAYER_ORDER: Record<string, number> = {
  API: 0,
  SECURITY: 0,
  SERVICE: 1,
  DOMAIN: 1,
  MESSAGING: 2,
  DATA: 2,
  TEST: 3,
};

const LAYER_STYLE: Record<string, { border: string; badge: string }> = {
  API: { border: '#111111', badge: 'text-[#111111] bg-stone-100 border-stone-300' },
  SERVICE: { border: '#059669', badge: 'text-emerald-800 bg-emerald-50 border-emerald-200' },
  DATA: { border: '#0284C7', badge: 'text-sky-800 bg-sky-50 border-sky-200' },
  MESSAGING: { border: '#D97706', badge: 'text-amber-800 bg-amber-50 border-amber-200' },
  TEST: { border: '#57534E', badge: 'text-stone-700 bg-stone-100 border-stone-200' },
  SECURITY: { border: '#DC2626', badge: 'text-red-700 bg-red-50 border-red-200' },
  DOMAIN: { border: '#78716C', badge: 'text-stone-700 bg-stone-100 border-stone-200' },
};

export function CodeIntelPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const repos = useQuery({ queryKey: ['repos'], queryFn: api.listRepos });

  const defaultRepo = useMemo(() => {
    const list = repos.data ?? [];
    if (list.length === 0) return '';
    return [...list].sort((a, b) => b.symbolCount - a.symbolCount)[0].id;
  }, [repos.data]);
  const selectedRepoId = searchParams.get('repo') || defaultRepo;
  const [selectedNode, setSelectedNode] = useState<string>('');
  const [selectedFileId, setSelectedFileId] = useState<string>('');
  const [searchInput, setSearchInput] = useState('executeTransfer');
  const [submittedQuery, setSubmittedQuery] = useState('executeTransfer');

  const graph = useQuery({
    queryKey: ['graph', selectedRepoId],
    queryFn: () => api.getDependencyGraph(selectedRepoId),
    enabled: !!selectedRepoId,
  });

  const files = useQuery({
    queryKey: ['files', selectedRepoId],
    queryFn: () => api.listRepoFiles(selectedRepoId),
    enabled: !!selectedRepoId,
  });

  useEffect(() => {
    if (graph.data?.nodes.length && !selectedNode) {
      const preferred =
        graph.data.nodes.find((n) => n.incomingCount > 0)?.id ?? graph.data.nodes[0].id;
      setSelectedNode(preferred);
    }
  }, [graph.data, selectedNode]);

  useEffect(() => {
    if (files.data?.length && !files.data.some((f) => f.id === selectedFileId)) {
      setSelectedFileId(files.data[0].id);
    }
  }, [files.data, selectedFileId]);

  const impact = useQuery({
    queryKey: ['impact', selectedRepoId, selectedNode],
    queryFn: () => api.getChangeImpact(selectedRepoId, selectedNode),
    enabled: !!selectedRepoId && !!selectedNode,
  });

  const fileDetail = useQuery({
    queryKey: ['fileDetail', selectedRepoId, selectedFileId],
    queryFn: () => api.getFileDetail(selectedRepoId, selectedFileId),
    enabled: !!selectedRepoId && !!selectedFileId,
  });

  const searchRes = useQuery({
    queryKey: ['codeSearch', selectedRepoId, submittedQuery],
    queryFn: () => api.searchCode(selectedRepoId, submittedQuery),
    enabled: !!selectedRepoId && submittedQuery.trim().length > 0,
  });

  const impactedSet = useMemo(
    () => new Set((impact.data?.impactedNodes ?? []).map((n) => n.nodeName)),
    [impact.data]
  );

  const flowNodes: Node[] = useMemo(() => {
    if (!graph.data) return [];
    const byColumn = new Map<number, typeof graph.data.nodes>();
    for (const n of graph.data.nodes) {
      const col = LAYER_ORDER[n.layer] ?? 1;
      const list = byColumn.get(col) ?? [];
      list.push(n);
      byColumn.set(col, list);
    }

    const result: Node[] = [];
    byColumn.forEach((colNodes, colIndex) => {
      colNodes.forEach((n, rowIdx) => {
        const isSelected = n.id === selectedNode;
        const isImpacted = impactedSet.has(n.id);
        const palette = LAYER_STYLE[n.layer] ?? LAYER_STYLE.DOMAIN;
        result.push({
          id: n.id,
          position: { x: colIndex * 255 + 30, y: rowIdx * 108 + 25 },
          data: {
            label: (
              <div className="text-left">
                <div className="flex items-center justify-between gap-2 mb-1">
                  <span
                    className={`text-[9px] font-mono px-1.5 py-0.5 rounded border ${
                      isSelected
                        ? 'bg-white/15 text-white border-white/25'
                        : isImpacted
                        ? 'bg-red-100 text-red-800 border-red-200'
                        : palette.badge
                    }`}
                  >
                    {n.layer}
                  </span>
                  <span
                    className={`text-[10px] font-mono ${
                      isSelected ? 'text-stone-300' : 'text-stone-500'
                    }`}
                  >
                    cc:{n.complexity}
                  </span>
                </div>
                <div
                  className={`text-xs font-mono font-semibold truncate ${
                    isSelected ? 'text-white' : isImpacted ? 'text-red-900' : 'text-[#111111]'
                  }`}
                >
                  {n.label}
                </div>
                <div
                  className={`text-[10px] mt-1 font-mono ${
                    isSelected ? 'text-stone-300' : 'text-stone-500'
                  }`}
                >
                  {n.symbolCount} symbols · {n.incomingCount} in / {n.outgoingCount} out
                </div>
              </div>
            ),
          },
          style: {
            width: 214,
            padding: 10,
            borderRadius: 10,
            background: isSelected ? '#111111' : isImpacted ? '#FEF2F2' : '#FFFFFF',
            border: isSelected
              ? '1.5px solid #111111'
              : isImpacted
              ? '1.5px solid #DC2626'
              : `1px solid #D6D3CD`,
            boxShadow: isSelected
              ? '0 6px 20px rgba(17,17,17,0.18)'
              : isImpacted
              ? '0 2px 10px rgba(220,38,38,0.12)'
              : '0 1px 2px rgba(0,0,0,0.04)',
            cursor: 'pointer',
          },
        });
      });
    });
    return result;
  }, [graph.data, selectedNode, impactedSet]);

  const flowEdges: Edge[] = useMemo(() => {
    if (!graph.data) return [];
    return graph.data.edges.map((e) => {
      const isHot = e.target === selectedNode || e.source === selectedNode;
      return {
        id: e.id,
        source: e.source,
        target: e.target,
        label: e.dependencyType,
        animated: isHot,
        labelStyle: {
          fill: isHot ? '#991B1B' : '#78716C',
          fontSize: 9,
          fontFamily: 'JetBrains Mono',
        },
        labelBgStyle: { fill: '#FAFAF8', fillOpacity: 0.95 },
        style: {
          stroke: isHot ? '#DC2626' : '#C8C5BE',
          strokeWidth: isHot ? 2 : 1.25,
        },
        markerEnd: {
          type: MarkerType.ArrowClosed,
          color: isHot ? '#DC2626' : '#A8A29E',
        },
      };
    });
  }, [graph.data, selectedNode]);

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="JavaParser AST & Semantic Index"
        title="Code"
        accentWord="intelligence."
        description="Inspect class-level dependency graphs on a drafting-table canvas, compute BFS change-impact blast radius before merging, explore source files in Monaco, and search AST symbols."
        actions={
          <select
            value={selectedRepoId}
            onChange={(e) => {
              setSelectedNode('');
              setSelectedFileId('');
              setSearchParams({ repo: e.target.value });
            }}
            className="h-9 px-3 rounded-md bg-white border border-[#DCDCD5] text-xs font-mono text-[#111111] focus:outline-none focus:border-[#111111]"
          >
            {(repos.data ?? []).map((r) => (
              <option key={r.id} value={r.id}>
                {r.fullName}
              </option>
            ))}
          </select>
        }
      />

      {!selectedRepoId ? (
        <EmptyState
          icon={GitFork}
          title="No repository selected"
          description="Connect a repository first to visualize its AST dependency graph."
        />
      ) : (
        <Tabs.Root defaultValue="graph" className="space-y-4">
          <Tabs.List className="inline-flex items-center gap-1 p-1 rounded-lg bg-white border border-[#E5E5E0]">
            {[
              { value: 'graph', label: 'Dependency Graph & Impact', icon: Radar },
              { value: 'explorer', label: 'Source Explorer (Monaco)', icon: FileCode2 },
              { value: 'search', label: 'Semantic & Symbol Search', icon: Search },
            ].map((tab) => {
              const Icon = tab.icon;
              return (
                <Tabs.Trigger
                  key={tab.value}
                  value={tab.value}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium text-stone-600 data-[state=active]:bg-[#111111] data-[state=active]:text-white transition-colors cursor-pointer"
                >
                  <Icon className="w-3.5 h-3.5" />
                  {tab.label}
                </Tabs.Trigger>
              );
            })}
          </Tabs.List>

          {/* TAB 1: Interactive React Flow Dependency Graph + Change Impact Panel */}
          <Tabs.Content value="graph" className="grid grid-cols-1 xl:grid-cols-3 gap-5">
            <Panel className="xl:col-span-2 overflow-hidden flex flex-col">
              <div className="px-4 py-3 border-b border-[#EFEFEA] bg-[#FAFAF8] flex flex-wrap items-center justify-between gap-2 text-xs">
                <span className="font-medium text-[#111111]">
                  AST Dependency Graph · Click any node to compute Change Impact
                </span>
                <div className="flex items-center gap-3 font-mono text-[11px] text-stone-500">
                  <span className="flex items-center gap-1">
                    <span className="w-2 h-2 rounded-xs bg-[#111111]" /> Selected
                  </span>
                  <span className="flex items-center gap-1">
                    <span className="w-2 h-2 rounded-xs bg-red-600" /> Blast Radius
                  </span>
                  <span>
                    {graph.data?.nodes.length ?? 0} nodes · {graph.data?.edges.length ?? 0} edges
                  </span>
                </div>
              </div>
              <div className="h-[520px] w-full bg-[#FAFAF8]">
                {graph.isLoading ? (
                  <SkeletonBlock className="h-full rounded-none" />
                ) : (
                  <ReactFlow
                    nodes={flowNodes}
                    edges={flowEdges}
                    onNodeClick={(_, node) => setSelectedNode(node.id)}
                    fitView
                  >
                    <Background color="#D6D3CD" gap={20} size={1} />
                    <Controls />
                  </ReactFlow>
                )}
              </div>
            </Panel>

            {/* Change Impact Inspector */}
            <Panel className="p-5 flex flex-col gap-4">
              <div>
                <div className="text-[10px] font-mono uppercase tracking-[0.12em] text-stone-500">
                  Change Impact Analysis (BFS)
                </div>
                <h3 className="text-base font-mono font-semibold text-[#111111] mt-1">
                  {selectedNode || 'Select a node'}
                </h3>
              </div>

              {impact.data ? (
                <>
                  <div className="grid grid-cols-3 gap-2">
                    <div className="p-2.5 rounded-lg bg-[#FAFAF8] border border-[#EAEAE4]">
                      <div className="text-[10px] font-mono text-stone-500">Impacted</div>
                      <div className="text-lg font-semibold text-[#111111] tabular-nums">
                        {impact.data.totalImpacted}
                      </div>
                    </div>
                    <div className="p-2.5 rounded-lg bg-[#FAFAF8] border border-[#EAEAE4]">
                      <div className="text-[10px] font-mono text-stone-500">Tests</div>
                      <div className="text-lg font-semibold text-[#111111] tabular-nums">
                        {impact.data.impactedTests}
                      </div>
                    </div>
                    <div className="p-2.5 rounded-lg bg-[#FAFAF8] border border-[#EAEAE4]">
                      <div className="text-[10px] font-mono text-stone-500">Blast Risk</div>
                      <Badge className={`mt-1 ${severityClasses(impact.data.riskLevel)}`}>
                        {impact.data.riskLevel}
                      </Badge>
                    </div>
                  </div>

                  <div>
                    <div className="text-xs font-semibold text-[#111111] mb-2">
                      Affected Downstream Callers &amp; Tests
                    </div>
                    {impact.data.impactedNodes.length === 0 ? (
                      <div className="text-xs text-stone-500 p-3 rounded-lg bg-[#FAFAF8] border border-[#EAEAE4]">
                        Leaf component — zero upstream callers depend on this symbol.
                      </div>
                    ) : (
                      <div className="space-y-1.5 max-h-56 overflow-y-auto">
                        {impact.data.impactedNodes.map((n) => (
                          <button
                            key={n.nodeName}
                            onClick={() => setSelectedNode(n.nodeName)}
                            className="w-full text-left px-3 py-2 rounded-lg bg-red-50/70 hover:bg-red-50 border border-red-200/80 flex items-center justify-between gap-2 cursor-pointer transition-colors"
                          >
                            <div className="min-w-0">
                              <div className="text-xs font-mono font-semibold text-red-900 truncate">
                                {n.nodeName}
                              </div>
                              <div className="text-[10px] font-mono text-stone-500 truncate">
                                {n.filePath}
                              </div>
                            </div>
                            <span className="text-[10px] font-mono text-red-700 shrink-0">
                              hop {n.hopDistance} · {n.relationship}
                            </span>
                          </button>
                        ))}
                      </div>
                    )}
                  </div>

                  <div>
                    <div className="text-xs font-semibold text-[#111111] mb-2">
                      Direct Outgoing Dependencies
                    </div>
                    <div className="flex flex-wrap gap-1.5">
                      {impact.data.directDependencies.length === 0 ? (
                        <span className="text-xs text-stone-500">None</span>
                      ) : (
                        impact.data.directDependencies.map((d) => (
                          <button
                            key={d}
                            onClick={() => setSelectedNode(d)}
                            className="px-2 py-1 rounded-md bg-[#FAFAF8] hover:bg-stone-100 border border-[#DCDCD5] font-mono text-[11px] text-[#111111] cursor-pointer transition-colors"
                          >
                            {d}
                          </button>
                        ))
                      )}
                    </div>
                  </div>
                </>
              ) : (
                <SkeletonBlock className="h-48" />
              )}
            </Panel>
          </Tabs.Content>

          {/* TAB 2: Source File Explorer + Dark Code Monaco Viewer */}
          <Tabs.Content value="explorer" className="grid grid-cols-1 xl:grid-cols-4 gap-5">
            <Panel className="overflow-hidden">
              <div className="px-4 py-3 border-b border-[#EFEFEA] bg-[#FAFAF8] text-xs font-semibold text-[#111111]">
                Indexed Files ({files.data?.length ?? 0})
              </div>
              <div className="divide-y divide-[#EFEFEA] max-h-[540px] overflow-y-auto">
                {(files.data ?? []).map((f) => (
                  <button
                    key={f.id}
                    onClick={() => setSelectedFileId(f.id)}
                    className={`w-full text-left px-3.5 py-2.5 transition-colors cursor-pointer ${
                      f.id === selectedFileId
                        ? 'bg-[#111111] text-white'
                        : 'hover:bg-[#FAFAF8] text-[#111111]'
                    }`}
                  >
                    <div className="text-xs font-mono font-medium truncate">
                      {f.filePath.split('/').pop()}
                    </div>
                    <div
                      className={`flex items-center justify-between text-[10px] font-mono mt-0.5 ${
                        f.id === selectedFileId ? 'text-stone-300' : 'text-stone-500'
                      }`}
                    >
                      <span className="truncate max-w-[160px]">{f.filePath}</span>
                      <span>cc:{f.complexityScore}</span>
                    </div>
                  </button>
                ))}
              </div>
            </Panel>

            <Panel className="xl:col-span-3 overflow-hidden">
              <div className="px-4 py-3 border-b border-[#EFEFEA] bg-[#FAFAF8] flex items-center justify-between text-xs font-mono">
                <span className="text-[#111111] font-medium truncate">
                  {fileDetail.data?.filePath ?? 'Select a file'}
                </span>
                {fileDetail.data && (
                  <span className="text-stone-500">
                    {fileDetail.data.lineCount} lines · cyclomatic complexity{' '}
                    {fileDetail.data.complexityScore}
                  </span>
                )}
              </div>
              <div className="h-[540px] bg-[#141416]">
                {fileDetail.data ? (
                  <Editor
                    height="100%"
                    language={fileDetail.data.language}
                    value={fileDetail.data.content}
                    theme="vs-dark"
                    options={{
                      readOnly: true,
                      minimap: { enabled: false },
                      fontSize: 13,
                      fontFamily: 'JetBrains Mono',
                      scrollBeyondLastLine: false,
                      padding: { top: 14 },
                    }}
                  />
                ) : (
                  <SkeletonBlock className="h-full rounded-none" />
                )}
              </div>
            </Panel>
          </Tabs.Content>

          {/* TAB 3: Semantic & Symbol Search */}
          <Tabs.Content value="search" className="space-y-4">
            <Panel className="p-3.5">
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  setSubmittedQuery(searchInput);
                }}
                className="flex items-center gap-2.5"
              >
                <Search className="w-4 h-4 text-stone-400 shrink-0 ml-1" />
                <input
                  value={searchInput}
                  onChange={(e) => setSearchInput(e.target.value)}
                  placeholder="Search symbols, methods, or concepts (e.g. executeTransfer, idempotency, velocity)..."
                  className="flex-1 bg-transparent text-sm text-[#111111] placeholder:text-stone-400 focus:outline-none"
                />
                <Button type="submit" size="sm">
                  Search AST
                </Button>
              </form>
            </Panel>

            {searchRes.data && (
              <div className="space-y-3">
                <div className="rounded-xl bg-[#FAFAF8] border border-[#DCDCD5] px-4 py-3 text-xs text-[#111111] leading-relaxed">
                  {searchRes.data.synthesizedAnswer}
                </div>
                {searchRes.data.hits.map((hit, idx) => (
                  <Panel key={idx} className="p-4 space-y-2.5">
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge className="bg-stone-100 text-[#111111] border-[#DCDCD5] font-mono">
                          {hit.symbolKind}
                        </Badge>
                        <span className="text-xs font-mono font-semibold text-[#111111]">
                          {hit.symbolName}
                        </span>
                        <span className="text-[11px] font-mono text-stone-500">
                          {hit.filePath}:{hit.lineNumber}
                        </span>
                      </div>
                      <span className="text-[11px] font-mono font-semibold text-emerald-700 tabular-nums">
                        score {(hit.relevanceScore * 100).toFixed(0)}%
                      </span>
                    </div>
                    <pre className="p-3 rounded-lg bg-[#141416] text-[11.5px] font-mono text-stone-200 overflow-x-auto">
                      {hit.snippet}
                    </pre>
                  </Panel>
                ))}
              </div>
            )}
          </Tabs.Content>
        </Tabs.Root>
      )}
    </div>
  );
}
