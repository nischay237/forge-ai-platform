import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import {
  buildEvidenceSnapshot,
  classifyActuatorHealth,
  classifyWorkflowOutcome,
  clearEngineeringEvidenceCache,
  deriveOverallSummary,
  fetchEngineeringEvidence,
  STALE_THRESHOLD_MS,
} from '../lib/engineeringEvidence';
import { SystemStatusIndicator, UnderTheHoodSection } from '../components/EngineeringStatus';
import { EngineeringReliabilityPage } from '../pages/EngineeringReliabilityPage';
import { LoginPage } from '../pages/LoginPage';

describe('Engineering Evidence Classification & Restrained Status UI', () => {
  beforeEach(() => {
    clearEngineeringEvidenceCache();
    vi.restoreAllMocks();
  });

  it('distinguishes verified, failed, stale, and unverified workflow and health outcomes', () => {
    const now = Date.parse('2026-11-27T12:00:00Z');
    const recentIso = new Date(now - 60_000).toISOString();
    const staleIso = new Date(now - STALE_THRESHOLD_MS - 3_600_000).toISOString();

    expect(classifyWorkflowOutcome('completed', 'success', recentIso, now)).toBe('verified');
    expect(classifyWorkflowOutcome('completed', 'success', staleIso, now)).toBe('stale');
    expect(classifyWorkflowOutcome('completed', 'failure', recentIso, now)).toBe('failed');
    expect(classifyWorkflowOutcome('in_progress', null, recentIso, now)).toBe('unverified');
    expect(classifyWorkflowOutcome(null, null, null, now)).toBe('unverified');

    expect(classifyActuatorHealth(true, 'UP')).toBe('verified');
    expect(classifyActuatorHealth(true, 'DOWN')).toBe('failed');
    expect(classifyActuatorHealth(false, null)).toBe('unverified');

    expect(deriveOverallSummary('verified', 'verified').status).toBe('verified');
    expect(deriveOverallSummary('verified', 'failed').status).toBe('failed');
    expect(deriveOverallSummary('verified', 'stale').status).toBe('stale');
    expect(deriveOverallSummary('unverified', 'unverified')).toEqual({
      status: 'unverified',
      label: 'System status: Not verified',
    });
  });

  it('returns honest Not verified states when both runtime probe and GitHub API fail (never fabricates green)', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('network offline'));

    const snap = await fetchEngineeringEvidence({ forceRefresh: true });
    expect(snap.overallStatus).toBe('unverified');
    expect(snap.overallLabel).toBe('System status: Not verified');
    expect(snap.runtimeHealth.status).toBe('unverified');
    expect(snap.ciRun.status).toBe('unverified');
    expect(snap.categories.cicd.status).toBe('unverified');
    expect(snap.categories.testing.status).toBe('unverified');
    expect(snap.categories.deployment.status).toBe('unverified');
  });

  it('renders SystemStatusIndicator and UnderTheHoodSection distinguishing verified, failed, stale & unverified cards', () => {
    const nowMs = Date.parse('2026-11-27T12:00:00Z');
    const freshIso = new Date(nowMs - 60_000).toISOString();
    const staleIso = new Date(nowMs - STALE_THRESHOLD_MS - 86_400_000).toISOString();

    const mixedSnapshot = buildEvidenceSnapshot({
      nowIso: new Date(nowMs).toISOString(),
      nowMs,
      healthOk: true,
      healthStatusText: 'UP',
      healthDetail: 'Live /actuator/health endpoint responded UP.',
      run: {
        id: 999,
        status: 'completed',
        conclusion: 'success',
        head_sha: 'df715272ee1844eb50c35ac2c4f8505fb020ff80',
        head_branch: 'main',
        updated_at: freshIso,
        html_url: 'https://github.com/nischay237/forge-ai-platform/actions/runs/999',
      },
      jobs: [
        {
          id: 1,
          name: 'Security, Config Hygiene & Compose Gate',
          status: 'completed',
          conclusion: 'success',
          completed_at: staleIso,
          html_url: 'https://github.com/nischay237/forge-ai-platform/actions/runs/999/job/1',
        },
        {
          id: 2,
          name: 'Backend (Java 21 + Spring Boot + ArchUnit + Postgres Testcontainers)',
          status: 'completed',
          conclusion: 'success',
          completed_at: freshIso,
          html_url: 'https://github.com/nischay237/forge-ai-platform/actions/runs/999/job/2',
        },
        {
          id: 3,
          name: 'Frontend (React + TypeScript + Vitest + Build + Audit)',
          status: 'completed',
          conclusion: 'failure',
          completed_at: freshIso,
          html_url: 'https://github.com/nischay237/forge-ai-platform/actions/runs/999/job/3',
        },
        // Docker job intentionally absent -> should show Not verified
      ],
      ciErrorDetail: null,
    });

    render(
      <MemoryRouter>
        <SystemStatusIndicator evidence={mixedSnapshot} />
        <UnderTheHoodSection evidence={mixedSnapshot} />
      </MemoryRouter>
    );

    const statusLink = screen.getByRole('link', { name: /System operational · CI verified/i });
    expect(statusLink).toHaveAttribute('href', '/engineering');

    expect(screen.getByRole('heading', { name: 'Under the Hood' })).toBeInTheDocument();
    expect(screen.getAllByText('Verified').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('Stale')).toBeInTheDocument();
    expect(screen.getByText('Failed')).toBeInTheDocument();
    expect(screen.getAllByText('Not verified').length).toBeGreaterThanOrEqual(1);
  });

  it('renders EngineeringReliabilityPage with real CI jobs, commit link & explicit unverified boundaries', () => {
    const nowMs = Date.parse('2026-11-27T12:00:00Z');
    const freshIso = new Date(nowMs - 60_000).toISOString();

    const verifiedSnap = buildEvidenceSnapshot({
      nowIso: new Date(nowMs).toISOString(),
      nowMs,
      healthOk: false,
      healthStatusText: null,
      healthDetail: 'Health probe unreachable.',
      run: {
        id: 45958101666,
        status: 'completed',
        conclusion: 'success',
        head_sha: 'df715272ee1844eb50c35ac2c4f8505fb020ff80',
        head_branch: 'main',
        updated_at: freshIso,
        html_url: 'https://github.com/nischay237/forge-ai-platform/actions/runs/45958101666',
      },
      jobs: [
        {
          id: 101,
          name: 'Docker Container Images (Frontend, Backend & Unified Render)',
          status: 'completed',
          conclusion: 'success',
          completed_at: freshIso,
          html_url: 'https://github.com/nischay237/forge-ai-platform/actions/runs/45958101666/job/101',
        },
      ],
      ciErrorDetail: null,
    });

    render(
      <MemoryRouter initialEntries={['/engineering']}>
        <EngineeringReliabilityPage evidenceOverride={verifiedSnap} />
      </MemoryRouter>
    );

    expect(screen.getByRole('heading', { name: /Engineering & Reliability/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /commit df71527/i })).toHaveAttribute(
      'href',
      'https://github.com/nischay237/forge-ai-platform/commit/df715272ee1844eb50c35ac2c4f8505fb020ff80'
    );
    expect(screen.getByText(/Docker Container Images/i)).toBeInTheDocument();
    expect(screen.getByText(/Code Coverage Percentage/i)).toBeInTheDocument();
    expect(screen.getAllByText('Not verified').length).toBeGreaterThanOrEqual(5);
  });

  it('exposes subtle Engineering & Reliability footer link on LoginPage homepage without promotional banners', () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('offline in test'));
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>
    );

    const footerLink = screen.getByRole('link', { name: 'Engineering & Reliability' });
    expect(footerLink).toHaveAttribute('href', '/engineering');
  });
});
