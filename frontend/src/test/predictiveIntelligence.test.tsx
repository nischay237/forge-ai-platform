import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PredictiveIntelligencePage } from '../pages/PredictiveIntelligencePage';
import { CommandPalette } from '../components/CommandPalette';
import { useForgeStore } from '../lib/store';

function makeQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

describe('Predictive Engineering Intelligence UI & Navigation', () => {
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

  it('renders PredictiveIntelligencePage with honest limited-data notice, 7 signal channels & separated test forecast', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const url = String(input);
      if (url.includes('/api/v1/predictions/overview')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              totalPredictions: 1,
              highRiskPredictions: 1,
              totalHistoricalTestExecutions: 0,
              totalDeployments: 0,
              totalRollbacks: 0,
              totalConfirmedIncidents: 0,
              totalConfirmedDefects: 0,
              limitedHistoricalData: true,
              historicalDataNotice:
                'No historical test executions, deployments, incidents, or defects have been ingested yet.',
              riskLevelDistribution: { CRITICAL: 0, HIGH: 1, MEDIUM: 0, LOW: 0 },
              recentPredictions: [
                {
                  predictionRunId: 'run-1',
                  reviewId: 'rev-1',
                  repositoryId: 'repo-1',
                  repositoryName: 'acme/payments',
                  prNumber: 42,
                  prTitle: 'feat: ledger update',
                  overallEngineeringRiskScore: 58,
                  overallRiskLevel: 'HIGH',
                  bugRiskLevel: 'HIGH',
                  testFailureRiskLevel: 'MEDIUM',
                  incidentRiskLevel: 'INSUFFICIENT_DATA',
                  changeFailureRiskLevel: 'INSUFFICIENT_DATA',
                  performanceRiskLevel: 'HIGH',
                  historicalSampleSize: 0,
                  engineVersion: 'forge-predictive-baseline-v1.0',
                  createdAt: new Date().toISOString(),
                },
              ],
              problematicModules: [],
              testReliability: [],
              deploymentOutcomes: [],
              recentIncidents: [],
              latestEvaluations: [
                {
                  id: 'ev-1',
                  repositoryId: null,
                  engineVersion: 'forge-predictive-baseline-v1.0',
                  category: 'BUG_RISK',
                  status: 'INSUFFICIENT_LABELLED_DATA',
                  sampleSize: 0,
                  positiveOutcomes: 0,
                  precisionScore: null,
                  recallScore: null,
                  f1Score: null,
                  falsePositiveRate: null,
                  falseNegativeRate: null,
                  calibrationError: null,
                  baselineComparison: {},
                  notes: 'Insufficient labelled historical outcomes for chronological held-out evaluation.',
                  evaluatedAt: new Date().toISOString(),
                },
              ],
            }),
            { status: 200 }
          )
        );
      }
      if (url.includes('/api/v1/reviews')) {
        return Promise.resolve(
          new Response(
            JSON.stringify([
              {
                id: 'rev-1',
                repositoryId: 'repo-1',
                repositoryName: 'acme/payments',
                prNumber: 42,
                title: 'feat: ledger update',
                status: 'COMPLETED',
              },
            ]),
            { status: 200 }
          )
        );
      }
      if (url.includes('/api/v1/repositories')) {
        return Promise.resolve(
          new Response(JSON.stringify([{ id: 'repo-1', fullName: 'acme/payments' }]), { status: 200 })
        );
      }
      if (url.includes('/api/v1/predictions/rev-1/evidence')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              predictionRunId: 'run-1',
              reviewId: 'rev-1',
              engineVersion: 'forge-predictive-baseline-v1.0',
              byFile: [
                {
                  scope: 'FILE',
                  name: 'src/main/java/LedgerService.java',
                  filePath: 'src/main/java/LedgerService.java',
                  riskLevel: 'HIGH',
                  riskScore: 62,
                  reason: 'N+1 DB query inside loop',
                },
              ],
              byClass: [],
              byMethod: [],
              byModule: [],
              byService: [],
              evidence: [],
            }),
            { status: 200 }
          )
        );
      }
      if (url.includes('/api/v1/predictions/rev-1/tests')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              predictionRunId: 'run-1',
              reviewId: 'rev-1',
              prNumber: 42,
              separationNotice:
                'Predicted tests at risk are static/historical forecasts and have NOT been executed by FORGE.',
              predictedTestsAtRisk: [
                {
                  testName: 'LedgerServiceTest',
                  testFilePath: 'src/test/java/LedgerServiceTest.java',
                  riskLevel: 'MEDIUM',
                  riskScore: 32,
                  confidenceLevel: 'LOW',
                  selectionReason: 'Direct unit test targeting changed class',
                  historicalEvidence: 'No prior per-test execution history recorded',
                  historicalRuns: 0,
                  historicalFailures: 0,
                  suggestedCommand: './mvnw test -Dtest=LedgerServiceTest',
                  predictionState: 'PREDICTED_AT_RISK',
                },
              ],
              actualCiTestResults: [],
              hasActualCiResults: false,
            }),
            { status: 200 }
          )
        );
      }
      if (url.includes('/api/v1/predictions/rev-1/runs')) {
        return Promise.resolve(
          new Response(
            JSON.stringify([
              {
                predictionRunId: 'run-1',
                commitSha: 'sha42abcdef',
                engineVersion: 'forge-predictive-baseline-v1.0',
                modelType: 'DETERMINISTIC_BASELINE_V1',
                overallEngineeringRiskScore: 58,
                overallRiskLevel: 'HIGH',
                historicalSampleSize: 0,
                createdAt: new Date().toISOString(),
              },
            ]),
            { status: 200 }
          )
        );
      }
      if (url.includes('/api/v1/predictions/rev-1/history')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              reviewId: 'rev-1',
              repositoryId: 'repo-1',
              repositoryName: 'acme/payments',
              limitedHistory: true,
              availabilityNotice: 'No historical outcomes recorded.',
              totalMatchingRecords: 0,
              relatedDefects: [],
              recentTestExecutions: [],
              relatedDeployments: [],
              relatedIncidents: [],
            }),
            { status: 200 }
          )
        );
      }
      if (url.includes('/api/v1/predictions/rev-1')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              predictionRunId: 'run-1',
              reviewId: 'rev-1',
              repositoryId: 'repo-1',
              repositoryName: 'acme/payments',
              prNumber: 42,
              prTitle: 'feat: ledger update',
              commitSha: 'sha42',
              engineVersion: 'forge-predictive-baseline-v1.0',
              modelType: 'DETERMINISTIC_BASELINE_V1',
              overallEngineeringRiskScore: 58,
              overallRiskLevel: 'HIGH',
              policyBoundaryNote: 'Predictive risk scores NEVER automatically block a PR.',
              historicalSampleSize: 0,
              dataFreshnessAt: null,
              dataAvailabilitySummary: 'No historical engineering outcomes recorded yet.',
              signalChannels: [
                {
                  channel: 'STATIC_CODE_ANALYSIS',
                  label: 'Static Code & Contract Analysis',
                  rawScore: 30,
                  deduplicatedContribution: 7,
                  riskLevel: 'MEDIUM',
                  signalKind: 'DETERMINISTIC_STATIC',
                  summary: '1 static finding',
                },
              ],
              predictions: [
                {
                  id: 'pi-1',
                  category: 'PERFORMANCE_REGRESSION',
                  riskLevel: 'HIGH',
                  riskScore: 60,
                  probability: null,
                  confidenceLevel: 'MEDIUM',
                  insufficientData: false,
                  historicalSampleSize: 0,
                  dataFreshnessAt: null,
                  ruleOrModelVersion: 'forge-perf-static-v1.0',
                  explanation: 'Static performance warning: N+1 query detected inside loop.',
                  limitations: 'No runtime benchmarks or production APM telemetry are connected.',
                  recommendedAction: 'Batch DB queries before production release.',
                  targetBreakdowns: [],
                  evidence: [],
                },
              ],
              featureSummary: {},
              createdAt: new Date().toISOString(),
            }),
            { status: 200 }
          )
        );
      }
      return Promise.resolve(new Response('{}', { status: 200 }));
    });

    render(
      <QueryClientProvider client={makeQueryClient()}>
        <MemoryRouter initialEntries={['/predictions?reviewId=rev-1']}>
          <PredictiveIntelligencePage />
        </MemoryRouter>
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(screen.getByText(/Predict What Could Fail Before Production/i)).toBeInTheDocument();
    });
    expect(screen.getByText(/Limited Historical Evidence/i)).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByText(/Omitted \(uncalibrated\)/i)).toBeInTheDocument();
    });
    expect(screen.getByText(/FORECAST ONLY — NOT EXECUTED/i)).toBeInTheDocument();
    expect(screen.getByText('LedgerServiceTest')).toBeInTheDocument();
    expect(screen.getByText('INSUFFICIENT_LABELLED_DATA')).toBeInTheDocument();
    expect(screen.getByText(/Workspace PR Predictive Risk Register/i)).toBeInTheDocument();
    expect(screen.getByText(/Recent Confirmed Incidents/i)).toBeInTheDocument();
    const graphLink = screen.getByRole('link', { name: /Dependency Graph/i });
    expect(graphLink).toHaveAttribute('href', '/code-intel?repo=repo-1');
  });

  it('navigates to /predictions on G+F chord in CommandPalette', async () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <CommandPalette />
        <Routes>
          <Route path="/" element={<div>HOME_VIEW</div>} />
          <Route path="/predictions" element={<div>PREDICTIONS_VIEW</div>} />
        </Routes>
      </MemoryRouter>
    );

    fireEvent.keyDown(window, { key: 'g' });
    fireEvent.keyDown(window, { key: 'f' });

    await waitFor(() => {
      expect(screen.getByText('PREDICTIONS_VIEW')).toBeInTheDocument();
    });
  });
});
