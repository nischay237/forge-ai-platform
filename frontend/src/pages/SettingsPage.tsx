import React, { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, KeyRound, Save, Server, Users } from 'lucide-react';
import { api } from '../lib/api';
import { useForgeStore } from '../lib/store';
import { Badge, Button, PageHeader, Panel, SkeletonBlock } from '../components/ui';

export function SettingsPage() {
  const qc = useQueryClient();
  const updateTenant = useForgeStore((s) => s.updateTenant);
  const settings = useQuery({ queryKey: ['settings'], queryFn: api.getSettings });
  const health = useQuery({ queryKey: ['actuatorHealth'], queryFn: api.getActuatorHealth });

  const [workspaceName, setWorkspaceName] = useState('');
  const [aiProvider, setAiProvider] = useState('HYBRID');
  const [promptVersion, setPromptVersion] = useState('forge-review-v2.4');
  const [requireApproval, setRequireApproval] = useState(true);
  const [newWebhookSecret, setNewWebhookSecret] = useState('');
  const [newGithubToken, setNewGithubToken] = useState('');
  const [savedNotice, setSavedNotice] = useState(false);

  useEffect(() => {
    if (settings.data?.tenant) {
      setWorkspaceName(settings.data.tenant.name);
      setAiProvider(settings.data.tenant.aiProvider);
      setPromptVersion(settings.data.tenant.promptVersion);
      setRequireApproval(settings.data.tenant.requireApprovalBeforePublish);
    }
  }, [settings.data]);

  const saveMutation = useMutation({
    mutationFn: api.updateSettings,
    onSuccess: (updated) => {
      updateTenant(updated);
      qc.invalidateQueries({ queryKey: ['settings'] });
      qc.invalidateQueries({ queryKey: ['audit'] });
      setNewWebhookSecret('');
      setNewGithubToken('');
      setSavedNotice(true);
      setTimeout(() => setSavedNotice(false), 3000);
    },
  });

  if (settings.isLoading) {
    return <SkeletonBlock className="h-96" />;
  }

  const data = settings.data;

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Tenant Administration & Governance"
        title="Workspace"
        accentWord="settings."
        description="Configure AI review provider policy, prompt version pinning, human-in-the-loop GitHub approval guard, HMAC webhook secret rotation, and RBAC team members."
      />

      <div className="grid grid-cols-1 xl:grid-cols-3 gap-5">
        {/* Governance Form */}
        <Panel className="xl:col-span-2 p-6">
          <form
            onSubmit={(e) => {
              e.preventDefault();
              saveMutation.mutate({
                workspaceName,
                aiProvider,
                promptVersion,
                requireApprovalBeforePublish: requireApproval,
                newWebhookSecret: newWebhookSecret.trim() || undefined,
                newGithubToken: newGithubToken.trim() || undefined,
              });
            }}
            className="space-y-5"
          >
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label className="block text-xs font-medium text-stone-700 mb-1.5">
                  Workspace Name
                </label>
                <input
                  value={workspaceName}
                  onChange={(e) => setWorkspaceName(e.target.value)}
                  className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs text-[#111111] focus:outline-none focus:border-[#111111]"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-stone-700 mb-1.5">
                  Tenant Slug &amp; Webhook Endpoint
                </label>
                <div className="h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#EAEAE4] flex items-center font-mono text-[11px] text-stone-600 truncate">
                  POST /api/v1/webhooks/github/{data?.tenant.slug}
                </div>
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label className="block text-xs font-medium text-stone-700 mb-1.5">
                  AI Review Engine Mode
                </label>
                <select
                  value={aiProvider}
                  onChange={(e) => setAiProvider(e.target.value)}
                  className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs text-[#111111] focus:outline-none focus:border-[#111111]"
                >
                  <option value="HYBRID">Hybrid (AST + Security + Spring AI OpenAI)</option>
                  <option value="DETERMINISTIC">Deterministic AST + Static Rules Only</option>
                </select>
              </div>
              <div>
                <label className="block text-xs font-medium text-stone-700 mb-1.5">
                  Pinned Prompt Version
                </label>
                <input
                  value={promptVersion}
                  onChange={(e) => setPromptVersion(e.target.value)}
                  className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs font-mono text-[#111111] focus:outline-none focus:border-[#111111]"
                />
              </div>
            </div>

            <div className="p-4 rounded-xl bg-[#FAFAF8] border border-[#DCDCD5] space-y-2.5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <label className="text-xs font-semibold text-[#111111] flex items-center gap-1.5">
                  <KeyRound className="w-3.5 h-3.5 text-[#111111]" />
                  GitHub Personal Access Token (Live PR Commenting)
                </label>
                <Badge
                  className={
                    data?.tenant.githubTokenConfigured
                      ? 'bg-emerald-50 text-emerald-800 border-emerald-200 font-mono'
                      : 'bg-amber-50 text-amber-800 border-amber-200 font-mono'
                  }
                >
                  {data?.tenant.githubTokenConfigured ? 'LIVE GITHUB CONNECTED' : 'NOT CONFIGURED'}
                </Badge>
              </div>
              <input
                type="password"
                value={newGithubToken}
                onChange={(e) => setNewGithubToken(e.target.value)}
                placeholder={
                  data?.tenant.githubTokenConfigured
                    ? `Connected token: ${data.tenant.maskedGithubToken} (enter new token to replace)`
                    : 'Paste ghp_... or github_pat_... (stored AES-256-GCM encrypted)'
                }
                className="w-full h-9 px-3 rounded-md bg-white border border-[#DCDCD5] text-xs font-mono text-[#111111] focus:outline-none focus:border-[#111111]"
              />
              <p className="text-[11px] text-stone-500 leading-relaxed">
                Required only to post human-approved review comments directly onto real GitHub Pull
                Requests. Create a classic token with <code className="font-mono">repo</code> scope
                (or fine-grained token with <code className="font-mono">Pull requests: Read &amp; write</code>)
                at <span className="font-mono text-[#111111]">github.com/settings/tokens</span>.
              </p>
            </div>

            <div>
              <label className="block text-xs font-medium text-stone-700 mb-1.5 flex items-center gap-1.5">
                <KeyRound className="w-3.5 h-3.5 text-stone-500" />
                Rotate GitHub Webhook HMAC Secret (AES-256-GCM encrypted at rest)
              </label>
              <input
                type="password"
                value={newWebhookSecret}
                onChange={(e) => setNewWebhookSecret(e.target.value)}
                placeholder={`Current masked: ${data?.tenant.maskedWebhookSecret ?? '••••'}`}
                className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs font-mono text-[#111111] focus:outline-none focus:border-[#111111]"
              />
            </div>

            <label className="flex items-start gap-3 p-3.5 rounded-lg bg-[#FAFAF8] border border-[#E5E5E0] cursor-pointer">
              <input
                type="checkbox"
                checked={requireApproval}
                onChange={(e) => setRequireApproval(e.target.checked)}
                className="mt-0.5 accent-[#111111]"
              />
              <div>
                <div className="text-xs font-medium text-[#111111]">
                  Require explicit human approval before publishing AI review comments to GitHub
                </div>
                <div className="text-[11px] text-stone-500 mt-0.5 leading-relaxed">
                  Safety guardrail: blocks unreviewed findings from being posted to pull requests
                  automatically.
                </div>
              </div>
            </label>

            <div className="flex items-center justify-between pt-2">
              {savedNotice ? (
                <span className="text-xs text-emerald-700 font-medium flex items-center gap-1.5">
                  <CheckCircle2 className="w-4 h-4" /> Settings saved &amp; audited.
                </span>
              ) : (
                <span className="text-[11px] font-mono text-stone-500">
                  Spring AI OpenAI Key: {data?.openaiConfigured ? 'CONFIGURED' : 'LOCAL HYBRID MODE'}
                </span>
              )}
              <Button type="submit" disabled={saveMutation.isPending}>
                <Save className="w-3.5 h-3.5" />
                {saveMutation.isPending ? 'Saving...' : 'Save Settings'}
              </Button>
            </div>
          </form>
        </Panel>

        {/* Right Column: Team RBAC + Live Actuator Health */}
        <div className="space-y-5">
          <Panel className="p-5">
            <h2 className="text-sm font-semibold text-[#111111] flex items-center gap-2 mb-3">
              <Users className="w-4 h-4 text-stone-500" />
              Workspace Members (RBAC)
            </h2>
            <div className="space-y-2.5">
              {(data?.members ?? []).map((m) => (
                <div
                  key={m.id}
                  className="p-3 rounded-lg bg-[#FAFAF8] border border-[#EAEAE4] flex items-center justify-between gap-2"
                >
                  <div className="min-w-0">
                    <div className="text-xs font-semibold text-[#111111] truncate">{m.fullName}</div>
                    <div className="text-[11px] font-mono text-stone-500 truncate">{m.email}</div>
                  </div>
                  <Badge className="bg-white text-[#111111] border-[#DCDCD5] font-mono">
                    {m.role}
                  </Badge>
                </div>
              ))}
            </div>
          </Panel>

          <Panel className="p-5">
            <h2 className="text-sm font-semibold text-[#111111] flex items-center gap-2 mb-3">
              <Server className="w-4 h-4 text-emerald-700" />
              Spring Actuator Health
            </h2>
            <div className="space-y-2 text-xs font-mono">
              <div className="flex justify-between">
                <span className="text-stone-500">Status</span>
                <span className="text-emerald-700 font-semibold">
                  {String(health.data?.status ?? 'UP')}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-stone-500">Active Profile</span>
                <span className="text-[#111111]">{data?.activeProfile ?? 'local'}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-stone-500">OpenAPI Docs</span>
                <a
                  href="/swagger-ui.html"
                  target="_blank"
                  rel="noreferrer"
                  className="text-[#111111] underline underline-offset-2 hover:text-stone-600"
                >
                  /swagger-ui.html
                </a>
              </div>
              <div className="flex justify-between">
                <span className="text-stone-500">Prometheus</span>
                <a
                  href="/actuator/prometheus"
                  target="_blank"
                  rel="noreferrer"
                  className="text-[#111111] underline underline-offset-2 hover:text-stone-600"
                >
                  /actuator/prometheus
                </a>
              </div>
            </div>
          </Panel>
        </div>
      </div>
    </div>
  );
}
