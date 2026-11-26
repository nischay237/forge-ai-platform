import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { applyPipelineEventToOperation, useForgeStore } from '../lib/store';
import { LiveProcessingPanel } from '../components/LiveProcessingPanel';
import { api } from '../lib/api';
import type { OperationStatusSnapshot, PipelineEvent } from '../lib/types';

function makeEvent(overrides: Partial<PipelineEvent>): PipelineEvent {
  return {
    eventId: `evt-${Math.random().toString(36).slice(2)}`,
    operationId: 'PR_REVIEW:rev-100',
    correlationId: 'corr-abc-123',
    sequence: 1,
    eventType: 'PR_REVIEW',
    resourceId: 'rev-100',
    stage: 'QUEUED',
    stageLabel: 'Request accepted & queued',
    status: 'STARTED',
    stageIndex: 1,
    totalStages: 8,
    progressPercent: 0,
    message: 'Queued PR #100 via Transactional Outbox',
    details: { prNumber: 100 },
    timestamp: '2026-11-26T18:00:00.000Z',
    ...overrides,
  };
}

describe('Real-Time SSE Processing Timeline & State Reducer', () => {
  beforeEach(() => {
    useForgeStore.setState({
      sseConnected: true,
      sseReconnecting: false,
      latestPipelineEvent: null,
      pipelineHistory: [],
      operationsById: {},
    });
    vi.restoreAllMocks();
  });

  it('deduplicates identical eventIds and ignores stale out-of-order regressions', () => {
    const e1 = makeEvent({ eventId: 'id-1', sequence: 1, stage: 'QUEUED', status: 'STARTED' });
    const e2 = makeEvent({
      eventId: 'id-2',
      sequence: 2,
      stage: 'INGESTING_DIFF',
      stageLabel: 'Parsing unified diff hunks',
      status: 'IN_PROGRESS',
      stageIndex: 2,
      message: 'Parsing 3 changed files',
    });
    const e3 = makeEvent({
      eventId: 'id-3',
      sequence: 3,
      stage: 'AST_IMPACT_ANALYSIS',
      stageLabel: 'Computing AST dependency blast radius',
      status: 'IN_PROGRESS',
      stageIndex: 3,
      message: 'Computing blast radius across 3 files',
    });

    let snap = applyPipelineEventToOperation(undefined, e1);
    snap = applyPipelineEventToOperation(snap, e3);
    // Out-of-order late arrival of sequence 2 must NOT regress currentStage back from AST_IMPACT_ANALYSIS
    snap = applyPipelineEventToOperation(snap, e2);
    // Exact duplicate of id-3 must NOT create a duplicate timeline row
    snap = applyPipelineEventToOperation(snap, e3);

    expect(snap.currentStage).toBe('AST_IMPACT_ANALYSIS');
    expect(snap.latestSequence).toBe(3);
    expect(snap.events).toHaveLength(3);
    expect(snap.events.map((e) => e.sequence)).toEqual([1, 2, 3]);
  });

  it('preserves terminal COMPLETED state against late IN_PROGRESS events and renders completion CTA', () => {
    const push = useForgeStore.getState().pushPipelineEvent;
    push(makeEvent({ eventId: 'c1', sequence: 1, stage: 'QUEUED', status: 'STARTED' }));
    push(
      makeEvent({
        eventId: 'c2',
        sequence: 8,
        stage: 'COMPLETED',
        stageLabel: 'Review complete & persisted',
        status: 'COMPLETED',
        stageIndex: 8,
        totalStages: 8,
        message: 'Review complete [WARN]: 2 finding(s) (Risk 42/100, 180ms)',
        details: { mergeDecision: 'WARN', riskScore: 42 },
        timestamp: '2026-11-26T18:00:02.500Z',
      })
    );
    // Late stale IN_PROGRESS frame after COMPLETED must not reopen RUNNING state
    push(
      makeEvent({
        eventId: 'c-stale',
        sequence: 5,
        stage: 'SECURITY_SCANNING',
        status: 'IN_PROGRESS',
        stageIndex: 5,
      })
    );

    render(
      <MemoryRouter>
        <LiveProcessingPanel
          resourceId="rev-100"
          eventTypes={['PR_REVIEW']}
          autoRecover={false}
          completionAction={{ label: 'Inspect Predictive Evidence', to: '/predictions?reviewId=rev-100' }}
        />
      </MemoryRouter>
    );

    expect(screen.getByText('Completed · 8 stages')).toBeInTheDocument();
    expect(screen.getAllByText(/Review complete \[WARN\]/).length).toBeGreaterThanOrEqual(1);
    expect(screen.getByRole('link', { name: /Inspect Predictive Evidence/i })).toHaveAttribute(
      'href',
      '/predictions?reviewId=rev-100'
    );

    // Expand technical diagnostics drawer
    fireEvent.click(screen.getByRole('button', { name: 'Details' }));
    expect(screen.getByText(/corr-abc-123/)).toBeInTheDocument();
    expect(screen.getByText(/PR_REVIEW:rev-100/)).toBeInTheDocument();
  });

  it('renders honest reconnection status while running and Retry action on terminal FAILED state', () => {
    const onRetry = vi.fn();
    useForgeStore.setState({ sseConnected: false, sseReconnecting: true });
    useForgeStore.getState().pushPipelineEvent(
      makeEvent({
        eventId: 'r1',
        sequence: 2,
        stage: 'AST_IMPACT_ANALYSIS',
        status: 'IN_PROGRESS',
        stageIndex: 3,
      })
    );

    const { rerender } = render(
      <MemoryRouter>
        <LiveProcessingPanel
          resourceId="rev-100"
          eventTypes={['PR_REVIEW']}
          autoRecover={false}
          onRetry={onRetry}
        />
      </MemoryRouter>
    );

    expect(screen.getByText('Reconnecting stream')).toBeInTheDocument();
    expect(screen.getByText(/Stage .* of 8/)).toBeInTheDocument();

    // Now emit FAILED terminal event
    act(() => {
      useForgeStore.getState().pushPipelineEvent(
        makeEvent({
          eventId: 'f1',
          sequence: 3,
          stage: 'FAILED',
          stageLabel: 'Review pipeline failed',
          status: 'FAILED',
          stageIndex: 8,
          totalStages: 8,
          message: 'Review pipeline encountered an error: diff parser timeout',
        })
      );
    });

    rerender(
      <MemoryRouter>
        <LiveProcessingPanel
          resourceId="rev-100"
          eventTypes={['PR_REVIEW']}
          autoRecover={false}
          onRetry={onRetry}
        />
      </MemoryRouter>
    );

    expect(screen.getByText('Failed')).toBeInTheDocument();
    expect(screen.getAllByText(/diff parser timeout/i).length).toBeGreaterThanOrEqual(1);
    fireEvent.click(screen.getByRole('button', { name: /Retry Operation/i }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('recovers exact server-side operation snapshot on page refresh via listStreamOperations', async () => {
    useForgeStore.setState({ token: 'jwt-valid-token' });
    const recovered: OperationStatusSnapshot = {
      operationId: 'PR_PREDICTION:rev-refresh-1',
      eventType: 'PR_PREDICTION',
      resourceId: 'rev-refresh-1',
      correlationId: 'corr-refresh-99',
      status: 'RUNNING',
      currentStage: 'EVALUATING_DEFECT_AND_TEST_RISK',
      currentStageLabel: 'Evaluating bug & test failure risk',
      completedStages: 1,
      totalStages: 5,
      latestSequence: 2,
      startedAt: '2026-11-26T18:05:00.000Z',
      updatedAt: '2026-11-26T18:05:01.000Z',
      completedAt: null,
      events: [
        makeEvent({
          eventId: 'p1',
          operationId: 'PR_PREDICTION:rev-refresh-1',
          eventType: 'PR_PREDICTION',
          resourceId: 'rev-refresh-1',
          sequence: 1,
          stage: 'EXTRACTING_FEATURES',
          stageLabel: 'Extracting diff, AST & historical signals',
          status: 'STARTED',
          stageIndex: 1,
          totalStages: 5,
          message: 'Extracting features for PR #77',
        }),
        makeEvent({
          eventId: 'p2',
          operationId: 'PR_PREDICTION:rev-refresh-1',
          eventType: 'PR_PREDICTION',
          resourceId: 'rev-refresh-1',
          sequence: 2,
          stage: 'EVALUATING_DEFECT_AND_TEST_RISK',
          stageLabel: 'Evaluating bug & test failure risk',
          status: 'IN_PROGRESS',
          stageIndex: 2,
          totalStages: 5,
          message: 'Scoring file/module defect susceptibility across 12 historical sample(s)',
        }),
      ],
    };

    const spy = vi.spyOn(api, 'listStreamOperations').mockResolvedValueOnce([recovered]);

    render(
      <MemoryRouter>
        <LiveProcessingPanel resourceId="rev-refresh-1" eventTypes={['PR_PREDICTION']} autoRecover />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(spy).toHaveBeenCalledWith('rev-refresh-1');
      expect(screen.getByText('Predictive Risk Engine')).toBeInTheDocument();
      expect(
        screen.getAllByText(/Scoring file\/module defect susceptibility/i).length
      ).toBeGreaterThanOrEqual(1);
    });
  });
});
