import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Activity, ArrowRight, Check, Lock, Network, ShieldCheck } from 'lucide-react';
import { api } from '../lib/api';
import { useForgeStore } from '../lib/store';
import { Button, ForgeMark } from '../components/ui';

export function LoginPage() {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('nischay@forge.dev');
  const [password, setPassword] = useState('ForgeUser@2026');
  const [workspaceName, setWorkspaceName] = useState('');
  const [fullName, setFullName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const setSession = useForgeStore((s) => s.setSession);
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const auth =
        mode === 'login'
          ? await api.login(email, password)
          : await api.register({ workspaceName, fullName, email, password });
      setSession(auth);
      navigate('/');
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Authentication failed');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-white text-[#111111] flex flex-col">
      {/* Crisp Minimal Top Nav */}
      <header className="h-14 border-b border-stone-200 px-6 sm:px-10 flex items-center justify-between bg-white">
        <div className="flex items-center gap-2.5">
          <ForgeMark className="w-6 h-6" />
          <span className="font-semibold text-[15px] tracking-[-0.03em]">Forge</span>
        </div>
        <div className="hidden sm:flex items-center gap-6 text-xs font-mono text-stone-500">
          <span>Java 21 LTS</span>
          <span>Spring Boot 3.5</span>
          <span>Kafka Outbox</span>
          <span>JavaParser AST</span>
        </div>
      </header>

      {/* Luminous Architectural Spectrum Grid Hero + Clean Auth Sheet */}
      <div
        className="flex-1 grid lg:grid-cols-12 items-center px-6 sm:px-12 py-10 gap-8 lg:gap-10 relative overflow-hidden"
        style={{
          backgroundImage: `
            linear-gradient(to right, rgba(255,255,255,0.44) 1px, transparent 1px),
            linear-gradient(to bottom, rgba(255,255,255,0.44) 1px, transparent 1px),
            radial-gradient(ellipse 60% 80% at 8% 32%, #1D6E86 0%, #3E9BB2 38%, transparent 72%),
            radial-gradient(ellipse 55% 65% at 62% 24%, #6EE7F5 0%, #C8F3FB 46%, transparent 76%),
            radial-gradient(ellipse 50% 60% at 54% 82%, #E5C8FA 0%, #F7D7EE 48%, transparent 75%),
            radial-gradient(ellipse 55% 70% at 92% 76%, #E85D1F 0%, #F58F4B 44%, transparent 78%),
            linear-gradient(135deg, #2B839C 0%, #98E6F2 36%, #F3E3FA 64%, #EF7D3B 100%)
          `,
          backgroundSize:
            '56px 56px, 56px 56px, 100% 100%, 100% 100%, 100% 100%, 100% 100%, 100% 100%',
        }}
      >
        {/* Left: Crisp Stepped Pure-White Editorial Cutout + 3 Capability Cards */}
        <div className="lg:col-span-7 max-w-2xl space-y-5">
          <div className="bg-white p-8 sm:p-10 shadow-[0_20px_60px_rgba(15,23,42,0.12)] border border-white/80 space-y-5">
            <div className="inline-flex items-center gap-2 text-[11px] font-mono uppercase tracking-[0.12em] text-stone-500">
              <span className="w-2 h-2 rounded-full bg-emerald-500" />
              Automated Code Review &amp; Release Safety · Java 21 + Spring Boot
            </div>

            <h1 className="text-3xl sm:text-[42px] font-medium tracking-[-0.04em] leading-[1.08] text-[#111111]">
              <span className="bg-[#EFE8FF]/85 px-1.5 box-decoration-clone">
                Catch bugs, leaks, and breaking changes before you merge.
              </span>
            </h1>

            <p className="text-sm sm:text-[15px] text-stone-700 leading-relaxed max-w-xl">
              Connect any real GitHub Pull Request. Forge scans the real diff for security bugs &amp;
              leaked API keys, maps affected services, and posts comments to GitHub only after human
              approval.
            </p>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3.5">
            {[
              {
                icon: Network,
                iconColor: 'text-sky-700',
                title: 'See Impact',
                items: ['Scan codebase structure', 'Spot affected services', 'Show tests to re-run'],
              },
              {
                icon: ShieldCheck,
                iconColor: 'text-emerald-700',
                title: 'Protect',
                items: ['Hide leaked API keys', 'Block prompt attacks', 'Human sign-off gate'],
              },
              {
                icon: Activity,
                iconColor: 'text-amber-700',
                title: 'Verify',
                items: ['Live GitHub PR sync', '0% false alarms (8/8)', 'Clean modular tests'],
              },
            ].map((card) => {
              const Icon = card.icon;
              return (
                <div
                  key={card.title}
                  className="p-4 rounded-2xl bg-white/95 backdrop-blur-md border border-white shadow-[0_14px_34px_rgba(15,23,42,0.10)] space-y-2.5"
                >
                  <div className="text-xs font-semibold text-[#111111] flex items-center gap-1.5">
                    <Icon className={`w-3.5 h-3.5 ${card.iconColor}`} />
                    {card.title}
                  </div>
                  <ul className="space-y-1.5">
                    {card.items.map((item) => (
                      <li
                        key={item}
                        className="flex items-center gap-1.5 text-[11.5px] text-stone-700 px-2 py-1 rounded-md bg-[#F8F8F6] border border-stone-200/60"
                      >
                        <Check className="w-3 h-3 text-[#111111] shrink-0" />
                        <span>{item}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              );
            })}
          </div>
        </div>

        {/* Right: Crisp Pure-White Sign-In Sheet */}
        <div className="lg:col-span-5 max-w-md w-full mx-auto">
          <div className="rounded-2xl bg-white border border-white p-7 sm:p-8 shadow-[0_20px_50px_rgba(15,23,42,0.14)] space-y-5">
            <div>
              <h2 className="text-lg font-semibold tracking-[-0.02em] text-[#111111]">
                {mode === 'login' ? 'Sign in to workspace' : 'Create isolated tenant workspace'}
              </h2>
              <p className="text-xs text-stone-500 mt-1">
                {mode === 'login'
                  ? 'Demo credentials pre-filled for Acme FinTech Core (OWNER).'
                  : 'Provision a new multi-tenant workspace backed by JWT & RBAC.'}
              </p>
            </div>

            <form onSubmit={handleSubmit} className="space-y-4">
              {mode === 'register' && (
                <>
                  <div>
                    <label className="block text-xs font-medium text-stone-700 mb-1">
                      Workspace Name
                    </label>
                    <input
                      required
                      value={workspaceName}
                      onChange={(e) => setWorkspaceName(e.target.value)}
                      placeholder="e.g. Payments Core Infra"
                      className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs text-[#111111] focus:outline-none focus:border-[#111111]"
                    />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-stone-700 mb-1">
                      Full Name
                    </label>
                    <input
                      required
                      value={fullName}
                      onChange={(e) => setFullName(e.target.value)}
                      placeholder="Nischay Srivastava"
                      className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs text-[#111111] focus:outline-none focus:border-[#111111]"
                    />
                  </div>
                </>
              )}

              <div>
                <label className="block text-xs font-medium text-stone-700 mb-1">Work Email</label>
                <input
                  required
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs font-mono text-[#111111] focus:outline-none focus:border-[#111111]"
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-stone-700 mb-1">Password</label>
                <input
                  required
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="w-full h-9 px-3 rounded-md bg-[#FAFAF8] border border-[#DCDCD5] text-xs font-mono text-[#111111] focus:outline-none focus:border-[#111111]"
                />
              </div>

              {error && (
                <div className="p-2.5 rounded-md bg-red-50 border border-red-200 text-xs text-red-700">
                  {error}
                </div>
              )}

              <Button type="submit" disabled={loading} className="w-full h-10">
                <Lock className="w-3.5 h-3.5" />
                <span>
                  {loading
                    ? 'Authenticating...'
                    : mode === 'login'
                    ? 'Sign In'
                    : 'Provision Workspace'}
                </span>
                <ArrowRight className="w-3.5 h-3.5" />
              </Button>
            </form>

            <div className="pt-3 border-t border-[#EFEFEA] text-center text-xs text-stone-500">
              {mode === 'login' ? (
                <>
                  Want to verify multi-tenant isolation?{' '}
                  <button
                    onClick={() => {
                      setMode('register');
                      setEmail('');
                      setPassword('');
                    }}
                    className="text-[#111111] font-semibold hover:underline cursor-pointer"
                  >
                    Create workspace
                  </button>
                </>
              ) : (
                <>
                  Already have a workspace?{' '}
                  <button
                    onClick={() => {
                      setMode('login');
                      setEmail('nischay@forge.dev');
                      setPassword('ForgeUser@2026');
                    }}
                    className="text-[#111111] font-semibold hover:underline cursor-pointer"
                  >
                    Sign in
                  </button>
                </>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
