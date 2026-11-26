import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CommandPalette } from '../components/CommandPalette';
import { ReviewDetailPage } from '../pages/ReviewDetailPage';
import { SettingsPage } from '../pages/SettingsPage';
import { useForgeStore } from '../lib/store';
import type { ReviewDetailDto, WorkspaceSettingsResponse } from '../lib/types';

vi.mock('@monaco-editor/react', () => ({
  default: () => <div data-testid="monaco-editor-mock" />,
  DiffEditor: () => <div data-testid="monaco-diff-mock" />,
}));

function makeTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  });
}

describe('Change Risk Review Detail, Command Palette Chords & Settings Health', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    useForgeStore.setState({
      token: 'jwt-owner',
      refreshToken: 'frt-owner',
      user: {
        id: 'u-1',
        tenantId: 't-1',
        email: 'owner@acme.io',
        fullName: 'Owner',
        role: 'OWNER',
        createdAt: new Date().toISOString(),
      },
      tenant: {
        id: 't-1',
        name: 'Acme',
        slug: 'acme',
        plan: 'ENTERPRISE',
        aiProvider: 'HYBRID',
        promptVersion: 'forge-review-v2.4',
        requireApprovalBeforePublish: true,
        maskedWebhookSecret: 'whsec_••••9999',
        maskedGithubToken: '',
        githubTokenConfigured: false,
        createdAt: new Date().toISOString(),
      },
    });
  });

  afterEach(() => {
    useForgeStore.getState().clearSession();
  });

  it('renders ReviewDetailPage with BLOCK decision, risk factors & NOT_EXECUTED selected tests', async () => {
    const mockDetail: ReviewDetailDto = {
      review: {
        id: 'rev-1',
        repositoryId: 'repo-1',
        repositoryName: 'acme/payments',
        prNumber: 42,
        title: 'feat: drop legacy ledger table',
        author: 'alice',
        sourceBranch: 'feat/drop',
        targetBranch: 'main',
        commitSha: 'abcdef123456',
        status: 'COMPLETED',
        currentStage: 'COMPLETED',
        riskScore: 86,
        mergeDecision: 'BLOCK',
        summary: 'MERGE BLOCKED due to destructive migration',
        promptVersion: 'forge-review-v2.4',
        modelUsed: 'forge-ast-deterministic-v2',
        tokensUsed: 420,
        estimatedCostUsd: 0,
        processingMs: 120,
        publishedToGithub: false,
        publishStatus: 'NOT_PUBLISHED',
        githubCommentUrl: null,
        findingCount: 1,
        criticalCount: 1,
        highCount: 0,
        createdAt: new Date().toISOString(),
        completedAt: new Date().toISOString(),
      },
      files: [
        {
          filePath: 'db/migration/V6__drop.sql',
          changeType: 'ADDED',
          language: 'sql',
          originalContent: '',
          modifiedContent: 'DROP TABLE legacy_ledger;',
          addedCount: 1,
          removedCount: 0,
        },
      ],
      findings: [],
      changeImpacts: [],
      riskAssessment: {
        riskScore: 86,
        mergeDecision: 'BLOCK',
        policyVersion: 2,
        decisionSummary: 'MERGE BLOCKED (Risk 86/100): Destructive migration detected.',
        safetyDisclaimer: 'PASS indicates no configured blocking policy condition was triggered.',
        triggeredPolicies: ['POLICY_BLOCK_DESTRUCTIVE_MIGRATION'],
        factors: [
          {
            category: 'DB_MIGRATION',
            title: 'Destructive DROP TABLE detected',
            severity: 'CRITICAL',
            riskPoints: 32,
            confidence: 0.96,
            evidence: 'db/migration/V6__drop.sql:1',
            explanation: 'Use expand-and-contract migration pattern.',
            policyRule: 'POLICY_BLOCK_DESTRUCTIVE_MIGRATION',
          },
        ],
        impactedModulesCount: 0,
        breakingApiCount: 0,
        migrationRiskCount: 1,
        criticalSecurityCount: 0,
      },
      testSelectionPlan: {
        executionStatus: 'NOT_EXECUTED',
        disclaimer: 'These tests were NOT executed by FORGE; run them in CI before merging.',
        prIncludesTestChanges: false,
        recommendedCount: 1,
        selectedTests: [
          {
            testName: 'LedgerMigrationTest',
            testFilePath: 'src/test/java/LedgerMigrationTest.java',
            selectionReason: 'Direct migration validation test',
            relatedChangedFile: 'db/migration/V6__drop.sql',
            priority: 'HIGH',
            suggestedCommand: './mvnw test -Dtest=LedgerMigrationTest',
            executionStatus: 'NOT_EXECUTED',
          },
        ],
        suggestedCommands: ['./mvnw test -Dtest=LedgerMigrationTest'],
      },
    };

    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify(mockDetail), { status: 200, headers: { 'Content-Type': 'application/json' } })
    );

    render(
      <QueryClientProvider client={makeTestQueryClient()}>
        <MemoryRouter initialEntries={['/reviews/rev-1']}>
          <Routes>
            <Route path="/reviews/:reviewId" element={<ReviewDetailPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('BLOCK')).toBeInTheDocument();
    });
    expect(screen.getAllByText('POLICY_BLOCK_DESTRUCTIVE_MIGRATION').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('NOT_EXECUTED')).toBeInTheDocument();
    expect(screen.getByText('LedgerMigrationTest')).toBeInTheDocument();
    expect(screen.getByText(/NOT executed by FORGE/i)).toBeInTheDocument();
  });

  it('navigates on real G+S keyboard chord in CommandPalette', async () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <CommandPalette />
        <Routes>
          <Route path="/" element={<div>HOME_VIEW</div>} />
          <Route path="/security" element={<div>SECURITY_VIEW</div>} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByText('HOME_VIEW')).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'g' });
    fireEvent.keyDown(window, { key: 's' });

    await waitFor(() => {
      expect(screen.getByText('SECURITY_VIEW')).toBeInTheDocument();
    });
  });

  it('displays UNREACHABLE health status when actuator health request fails', async () => {
    const mockSettings: WorkspaceSettingsResponse = {
      tenant: useForgeStore.getState().tenant!,
      members: [useForgeStore.getState().user!],
      activeProfile: 'local',
      openaiConfigured: false,
    };

    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const url = String(input);
      if (url.includes('/api/v1/settings')) {
        return Promise.resolve(new Response(JSON.stringify(mockSettings), { status: 200 }));
      }
      if (url.includes('/api/v1/policies')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              id: 'pol-1',
              repositoryId: null,
              version: 1,
              blockOnCriticalSecurity: true,
              blockOnBreakingApi: true,
              blockOnDestructiveMigration: true,
              blockOnPromptInjection: true,
              maxRiskScoreForPass: 25,
              maxRiskScoreForWarn: 65,
              minTestsForHighImpact: true,
              sensitivePaths: ['auth'],
              architectureRules: [],
              updatedBy: 'owner@acme.io',
              updatedAt: new Date().toISOString(),
            }),
            { status: 200 }
          )
        );
      }
      return Promise.resolve(new Response('Service Unavailable', { status: 503 }));
    });

    render(
      <QueryClientProvider client={makeTestQueryClient()}>
        <MemoryRouter>
          <SettingsPage />
        </MemoryRouter>
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('UNREACHABLE')).toBeInTheDocument();
    });
    expect(screen.getByText(/Change Risk Policy-as-Code/i)).toBeInTheDocument();
  });
});
