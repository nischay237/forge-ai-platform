import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, Copy, KeyRound, RefreshCw, Save, Scale, Server, Trash2, Users } from 'lucide-react';
import { api } from '../lib/api';
import { useForgeStore } from '../lib/store';
import { Badge, Button, ErrorBanner, PageHeader, Panel, SkeletonBlock } from '../components/ui';

export function SettingsPage() {
  const qc = useQueryClient();
  const updateTenant = useForgeStore((s) => s.updateTenant);
  const initialWebhookSecret = useForgeStore((s) => s.initialWebhookSecret);
  const dismissInitialWebhookSecret = useForgeStore((s) => s.dismissInitialWebhookSecret);

  const settings = useQuery({ queryKey: ['settings'], queryFn: api.getSettings });
  const policy = useQuery({ queryKey: ['policy'], queryFn: () => api.getPolicy() });
  const health = useQuery({ queryKey: ['actuatorHealth'], queryFn: api.getActuatorHealth, retry: 0 });

  const [workspaceName, setWorkspaceName] = useState('');
  const [aiProvider, setAiProvider] = useState('HYBRID');
  const [promptVersion, setPromptVersion] = useState('forge-review-v2.4');
  const [requireApproval, setRequireApproval] = useState(true);
  const [newWebhookSecret, setNewWebhookSecret] = useState('');
  const [newGithubToken, setNewGithubToken] = useState('');
  const [rotatedSecret, setRotatedSecret] = useState<string | null>(null);
  const [savedNotice, setSavedNotice] = useState<string | null>(null);

  // Policy-as-Code form state
  const [blockCritSec, setBlockCritSec] = useState(true);
  const [blockBreakingApi, setBlockBreakingApi] = useState(true);
  const [blockDestructiveMig, setBlockDestructiveMig] = useState(true);
  const [blockPromptInj, setBlockPromptInj] = useState(true);
  const [minTestsHighImpact, setMinTestsHighImpact] = useState(true);
  const [passMax, setPassMax] = useState(25);
  const [warnMax, setWarnMax] = useState(65);
  const [policyValidationError, setPolicyValidationError] = useState<string | null>(null);

  useEffect(() => {
    if (settings.data?.tenant) {
      setWorkspaceName(settings.data.tenant.name);
      setAiProvider(settings.data.tenant.aiProvider);
      setPromptVersion(settings.data.tenant.promptVersion);
      setRequireApproval(settings.data.tenant.requireApprovalBeforePublish);
    }
  }, [settings.data]);

  useEffect(() => {
    if (policy.data) {
      setBlockCritSec(policy.data.blockOnCriticalSecurity);
      setBlockBreakingApi(policy.data.blockOnBreakingApi);
      setBlockDestructiveMig(policy.data.blockOnDestructiveMigration);
      setBlockPromptInj(policy.data.blockOnPromptInjection);
      setMinTestsHighImpact(policy.data.minTestsForHighImpact);
      setPassMax(policy.data.maxRiskScoreForPass);
      setWarnMax(policy.data.maxRiskScoreForWarn);
    }
  }, [policy.data]);

  const saveMutation = useMutation({
    mutationFn: api.updateSettings,
    onSuccess: (updated) => {
      updateTenant(updated);
      qc.invalidateQueries({ queryKey: ['settings'] });
      qc.invalidateQueries({ queryKey: ['audit'] });
      setNewWebhookSecret('');
      setNewGithubToken('');
      setSavedNotice('Workspace settings saved and audited.');
    },
  });

  const rotateMutation = useMutation({
    mutationFn: api.rotateWebhookSecret,
    onSuccess: (resp) => {
      updateTenant(resp.tenant);
      setRotatedSecret(resp.webhookSecret);
      qc.invalidateQueries({ queryKey: ['settings'] });
      qc.invalidateQueries({ queryKey: ['audit'] });
    },
  });

  const policyMutation = useMutation({
    mutationFn: api.updatePolicy,
    onSuccess: (updated) => {
      qc.invalidateQueries({ queryKey: ['policy'] });
      qc.invalidateQueries({ queryKey: ['audit'] });
      setSavedNotice(`Published Change Risk Policy v${updated.version}.`);
    },
  });

  if (settings.isLoading) {
    return <SkeletonBlock className="h-96" />;
  }

  if (settings.isError) {
    return (
      <ErrorBanner
        message={settings.error instanceof Error ? settings.error.message : 'Unable to load workspace settings'}
        onRetry={() => settings.refetch()}
      />
    );
  }

  const data = settings.data;
  const revealedSecret = rotatedSecret || initialWebhookSecret;
  const healthStatus = health.isLoading
    ? 'CHECKING...'
    : health.isError
    ? 'UNREACHABLE'
    : String(health.data?.status ?? 'UNKNOWN');

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Tenant Administration, Secrets & Policy-as-Code"
        title="Workspace"
        accentWord="settings."
        description="Manage AI provider mode, human-in-the-loop GitHub guardrails, per-tenant encrypted credentials, and deterministic Change Risk merge policies."
      />

      {revealedSecret && (
        <Panel className="p-4 bg-[#111111] text-white border-[#111111] space-y-2">
          <div className="flex items-center justify-between gap-2">
            <span className="text-xs font-mono font-semibold uppercase tracking-wider">
              One-Time Per-Tenant GitHub Webhook Secret (Save Now)
            </span>
            <Button
              size="sm"
              variant="secondary"
              onClick={() => {
                navigator.clipboard?.writeText(revealedSecret);
              }}
            >
              <Copy className="w-3 h-3" /> Copy Secret
            </Button>
          </div>
          <code className="block font-mono text-xs bg-white/10 px-3 py-2 rounded break-all">
            {revealedSecret}
          </code>
          <div className="flex items-center justify-between text-[11px] text-stone-300">
            <span>This secret is stored AES-256-GCM encrypted with tenant AAD and will not be shown again.</span>
            <button
              type="button"
              onClick={() => {
                setRotatedSecret(null);
                dismissInitialWebhookSecret();
              }}
              className="underline cursor-pointer"
            >
              Dismiss
            </button>
          </div>
        </Panel>
      )}

      {savedNotice && (
        <div className="rounded-lg bg-emerald-50 border border-emerald-200 px-4 py-2.5 text-xs text-emerald-900 flex items-center justify-between">
          <span className="flex items-center gap-1.5">
            <CheckCircle2 className="w-4 h-4 text-emerald-700" />
            {savedNotice}
          </span>
          <button onClick={() => setSavedNotice(null)} className="text-xs underline cursor-pointer">
            Dismiss
          </button>
        </div>
      )}

      {saveMutation.isError && (
        <ErrorBanner
          message={saveMutation.error instanceof Error ? saveMutation.error.message : 'Failed to save settings'}
        />
      )}
      {policyMutation.isError && (
        <ErrorBanner
          message={policyMutation.error instanceof Error ? policyMutation.error.message : 'Failed to update policy'}
        />
      )}
      {policyValidationError && <ErrorBanner message={policyValidationError} />}

      <div className="grid grid-cols-1 xl:grid-cols-3 gap-5">
        <div className="xl:col-span-2 space-y-5">
          {/* Governance & Credentials Form */}
          <Panel className="p-6">
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
                  <label htmlFor="set-workspace" className="block text-xs font-medium text-stone-700 mb-1.5">
                    Workspace Name
                  </label>
                  <input
                    id="set-workspace"
                    value={workspaceName}
                    onChange={(e) => setWorkspaceName(e.target.value)}
                    className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs text-[#111111] focus:outline-none focus:border-[#111111]"
                  />
                </div>
                <div>
                  <span className="block text-xs font-medium text-stone-700 mb-1.5">
                    Tenant Slug &amp; Webhook Endpoint
                  </span>
                  <div className="h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#EAEAE4] flex items-center font-mono text-[11px] text-stone-600 truncate">
                    POST /api/v1/webhooks/github/{data?.tenant.slug}
                  </div>
                </div>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <label htmlFor="set-ai-provider" className="block text-xs font-medium text-stone-700 mb-1.5">
                    AI Review Engine Mode
                  </label>
                  <select
                    id="set-ai-provider"
                    value={aiProvider}
                    onChange={(e) => setAiProvider(e.target.value)}
                    className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs text-[#111111] focus:outline-none focus:border-[#111111]"
                  >
                    <option value="HYBRID">Hybrid (Deterministic AST + Optional LLM)</option>
                    <option value="DETERMINISTIC">Deterministic Only (Zero External LLM Calls)</option>
                  </select>
                </div>
                <div>
                  <label htmlFor="set-prompt-ver" className="block text-xs font-medium text-stone-700 mb-1.5">
                    Pinned Prompt Version
                  </label>
                  <select
                    id="set-prompt-ver"
                    value={promptVersion}
                    onChange={(e) => setPromptVersion(e.target.value)}
                    className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs font-mono text-[#111111] focus:outline-none focus:border-[#111111]"
                  >
                    <option value="forge-review-v2.4">forge-review-v2.4</option>
                    <option value="forge-review-v2.5">forge-review-v2.5</option>
                  </select>
                </div>
              </div>

              <div className="p-4 rounded-xl bg-[#FAFAF8] border border-[#DCDCD5] space-y-2.5">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <label htmlFor="set-gh-token" className="text-xs font-semibold text-[#111111] flex items-center gap-1.5">
                    <KeyRound className="w-3.5 h-3.5 text-[#111111]" />
                    Workspace GitHub Token (Strictly Tenant-Isolated)
                  </label>
                  <div className="flex items-center gap-2">
                    <Badge
                      className={
                        data?.tenant.githubTokenConfigured
                          ? 'bg-emerald-50 text-emerald-800 border-emerald-200 font-mono'
                          : 'bg-amber-50 text-amber-800 border-amber-200 font-mono'
                      }
                    >
                      {data?.tenant.githubTokenConfigured ? 'CONNECTED' : 'NOT CONFIGURED'}
                    </Badge>
                    {data?.tenant.githubTokenConfigured && (
                      <Button
                        type="button"
                        size="sm"
                        variant="danger"
                        disabled={saveMutation.isPending}
                        onClick={() => saveMutation.mutate({ newGithubToken: '' })}
                      >
                        <Trash2 className="w-3 h-3" /> Remove Token
                      </Button>
                    )}
                  </div>
                </div>
                <input
                  id="set-gh-token"
                  type="password"
                  value={newGithubToken}
                  onChange={(e) => setNewGithubToken(e.target.value)}
                  placeholder={
                    data?.tenant.githubTokenConfigured
                      ? `Connected: ${data.tenant.maskedGithubToken} (paste new token to replace)`
                      : 'Paste ghp_... or github_pat_... (stored AES-256-GCM + tenant AAD)'
                  }
                  className="w-full h-9 px-3 rounded-md bg-white border border-[#DCDCD5] text-xs font-mono text-[#111111] focus:outline-none focus:border-[#111111]"
                />
              </div>

              <div className="p-4 rounded-xl bg-[#FAFAF8] border border-[#DCDCD5] space-y-2.5">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <label htmlFor="set-wh-secret" className="text-xs font-semibold text-[#111111] flex items-center gap-1.5">
                    <KeyRound className="w-3.5 h-3.5 text-stone-500" />
                    Per-Tenant GitHub Webhook HMAC Secret
                  </label>
                  <Button
                    type="button"
                    size="sm"
                    variant="secondary"
                    disabled={rotateMutation.isPending}
                    onClick={() => rotateMutation.mutate()}
                  >
                    <RefreshCw className="w-3 h-3" />
                    {rotateMutation.isPending ? 'Rotating...' : 'Generate New Secret'}
                  </Button>
                </div>
                <input
                  id="set-wh-secret"
                  type="password"
                  value={newWebhookSecret}
                  onChange={(e) => setNewWebhookSecret(e.target.value)}
                  placeholder={`Current masked: ${data?.tenant.maskedWebhookSecret ?? '••••'} (min 16 chars to set manually)`}
                  className="w-full h-9 px-3 rounded-md bg-white border border-[#DCDCD5] text-xs font-mono text-[#111111] focus:outline-none focus:border-[#111111]"
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
                    Require explicit human approval before publishing comments to GitHub
                  </div>
                  <div className="text-[11px] text-stone-500 mt-0.5 leading-relaxed">
                    Blocks unreviewed findings from being posted onto live pull requests automatically.
                  </div>
                </div>
              </label>

              <div className="flex items-center justify-between pt-2">
                <span className="text-[11px] font-mono text-stone-500">
                  Server OpenAI Key: {data?.openaiConfigured ? 'CONFIGURED' : 'DETERMINISTIC / OFFLINE'}
                </span>
                <Button type="submit" disabled={saveMutation.isPending}>
                  <Save className="w-3.5 h-3.5" />
                  {saveMutation.isPending ? 'Saving...' : 'Save Workspace Settings'}
                </Button>
              </div>
            </form>
          </Panel>

          {/* Policy-as-Code Change Risk Engine Configuration */}
          <Panel className="p-6 space-y-4">
            <div className="flex items-center justify-between">
              <div>
                <h2 className="text-sm font-semibold text-[#111111] flex items-center gap-2">
                  <Scale className="w-4 h-4 text-[#111111]" />
                  Change Risk Policy-as-Code
                </h2>
                <p className="text-xs text-stone-500 mt-0.5">
                  Configure deterministic blocking rules and PASS/WARN/BLOCK score thresholds.
                </p>
              </div>
              {policy.data && (
                <Badge className="bg-[#111111] text-white border-[#111111]">
                  Policy v{policy.data.version}
                </Badge>
              )}
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
              {[
                { label: 'Block on Critical Security / Leaked Secrets', val: blockCritSec, set: setBlockCritSec },
                { label: 'Block on Breaking OpenAPI / REST Changes', val: blockBreakingApi, set: setBlockBreakingApi },
                { label: 'Block on Destructive SQL Migrations', val: blockDestructiveMig, set: setBlockDestructiveMig },
                { label: 'Block on Prompt Injection Attempts', val: blockPromptInj, set: setBlockPromptInj },
                { label: 'Warn on High-Impact PRs Missing Tests', val: minTestsHighImpact, set: setMinTestsHighImpact },
              ].map((item) => (
                <label
                  key={item.label}
                  className="flex items-center gap-2.5 p-3 rounded-lg bg-[#FAFAF8] border border-[#E5E5E0] cursor-pointer"
                >
                  <input
                    type="checkbox"
                    checked={item.val}
                    onChange={(e) => item.set(e.target.checked)}
                    className="accent-[#111111]"
                  />
                  <span className="text-[#111111] font-medium">{item.label}</span>
                </label>
              ))}
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label htmlFor="pol-pass-max" className="block text-xs font-medium text-stone-700 mb-1">
                  Max Score for PASS (0–99)
                </label>
                <input
                  id="pol-pass-max"
                  type="number"
                  min={0}
                  max={99}
                  value={passMax}
                  onChange={(e) => setPassMax(Number(e.target.value))}
                  className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs font-mono text-[#111111]"
                />
              </div>
              <div>
                <label htmlFor="pol-warn-max" className="block text-xs font-medium text-stone-700 mb-1">
                  Max Score for WARN (above = BLOCK)
                </label>
                <input
                  id="pol-warn-max"
                  type="number"
                  min={1}
                  max={100}
                  value={warnMax}
                  onChange={(e) => setWarnMax(Number(e.target.value))}
                  className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs font-mono text-[#111111]"
                />
              </div>
            </div>

            <div className="flex justify-end">
              <Button
                type="button"
                disabled={policyMutation.isPending}
                onClick={() => {
                  if (Number.isNaN(passMax) || Number.isNaN(warnMax) || passMax < 0 || warnMax > 100 || passMax >= warnMax) {
                    setPolicyValidationError(
                      'Max Score for PASS must be strictly less than Max Score for WARN (valid range 0–100).'
                    );
                    return;
                  }
                  setPolicyValidationError(null);
                  policyMutation.mutate({
                    blockOnCriticalSecurity: blockCritSec,
                    blockOnBreakingApi: blockBreakingApi,
                    blockOnDestructiveMigration: blockDestructiveMig,
                    blockOnPromptInjection: blockPromptInj,
                    minTestsForHighImpact: minTestsHighImpact,
                    maxRiskScoreForPass: passMax,
                    maxRiskScoreForWarn: warnMax,
                  });
                }}
              >
                <Save className="w-3.5 h-3.5" />
                {policyMutation.isPending ? 'Publishing Policy...' : 'Publish Risk Policy'}
              </Button>
            </div>
          </Panel>
        </div>

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
                <span
                  className={
                    healthStatus === 'UP'
                      ? 'text-emerald-700 font-semibold'
                      : healthStatus === 'UNREACHABLE'
                      ? 'text-red-700 font-semibold'
                      : 'text-amber-700 font-semibold'
                  }
                >
                  {healthStatus}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-stone-500">Active Profile</span>
                <span className="text-[#111111]">{data?.activeProfile ?? 'local'}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-stone-500">OpenAPI Docs</span>
                {(data?.activeProfile ?? '').toLowerCase().includes('prod') ? (
                  <span className="text-stone-500">Disabled in prod</span>
                ) : (
                  <a
                    href="/swagger-ui.html"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-[#111111] underline underline-offset-2 hover:text-stone-600"
                  >
                    /swagger-ui.html
                  </a>
                )}
              </div>
            </div>
          </Panel>
        </div>
      </div>
    </div>
  );
}
