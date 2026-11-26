import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api } from '../lib/api';
import { useForgeStore } from '../lib/store';
import type { PipelineEvent } from '../lib/types';

/**
 * Connects to FORGE's live SSE pipeline using a short-lived, single-use ticket obtained via
 * authenticated POST /api/v1/stream/ticket so long-lived JWTs never appear in query URLs.
 * Automatically hydrates active/recent operations from GET /api/v1/stream/operations on mount
 * and reconnect so browser refreshes and network drops never lose pipeline state.
 */
export function usePipelineStream() {
  const token = useForgeStore((s) => s.token);
  const setSseConnected = useForgeStore((s) => s.setSseConnected);
  const pushPipelineEvent = useForgeStore((s) => s.pushPipelineEvent);
  const hydrateOperations = useForgeStore((s) => s.hydrateOperations);
  const queryClient = useQueryClient();

  useEffect(() => {
    if (!token) {
      setSseConnected(false, false);
      return;
    }

    let cancelled = false;
    let es: EventSource | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let attempt = 0;

    const recoverOperations = async () => {
      try {
        const recent = await api.listStreamOperations();
        if (!cancelled) {
          hydrateOperations(recent);
        }
      } catch {
        // best-effort recovery
      }
    };

    const connect = async () => {
      if (cancelled || !useForgeStore.getState().token) return;
      try {
        const { ticket } = await api.issueSseTicket();
        if (cancelled) return;

        const url = `/api/v1/stream/events?ticket=${encodeURIComponent(ticket)}`;
        es = new EventSource(url);

        es.addEventListener('connected', () => {
          attempt = 0;
          setSseConnected(true, false);
          // Hydrate server-side snapshots AFTER stream subscription opens so no gap can drop events
          recoverOperations();
        });

        es.addEventListener('pipeline', (event) => {
          try {
            const parsed = JSON.parse((event as MessageEvent).data) as PipelineEvent;
            pushPipelineEvent(parsed);
            const isTerminal =
              parsed.status === 'COMPLETED' ||
              parsed.status === 'FAILED' ||
              parsed.stage === 'COMPLETED' ||
              parsed.stage === 'FAILED' ||
              parsed.stage === 'PREDICTION_COMPLETED';

            if (parsed.eventType === 'PR_REVIEW') {
              queryClient.invalidateQueries({ queryKey: ['reviews'] });
              queryClient.invalidateQueries({ queryKey: ['review', parsed.resourceId] });
              if (isTerminal) {
                queryClient.invalidateQueries({ queryKey: ['overview'] });
                queryClient.invalidateQueries({ queryKey: ['security'] });
                queryClient.invalidateQueries({ queryKey: ['audit'] });
              }
            } else if (parsed.eventType === 'REPO_INDEX') {
              queryClient.invalidateQueries({ queryKey: ['repos'] });
              queryClient.invalidateQueries({ queryKey: ['graph', parsed.resourceId] });
              queryClient.invalidateQueries({ queryKey: ['drift', parsed.resourceId] });
              queryClient.invalidateQueries({ queryKey: ['overview'] });
            } else if (parsed.eventType === 'PR_PREDICTION') {
              queryClient.invalidateQueries({ queryKey: ['prediction-overview'] });
              queryClient.invalidateQueries({ queryKey: ['prediction', parsed.resourceId] });
              queryClient.invalidateQueries({ queryKey: ['prediction-evidence', parsed.resourceId] });
              queryClient.invalidateQueries({ queryKey: ['prediction-tests', parsed.resourceId] });
              queryClient.invalidateQueries({ queryKey: ['prediction-history', parsed.resourceId] });
              queryClient.invalidateQueries({ queryKey: ['prediction-runs', parsed.resourceId] });
            }
          } catch {
            // ignore malformed SSE frames
          }
        });

        es.onerror = () => {
          es?.close();
          es = null;
          if (!cancelled && useForgeStore.getState().token) {
            setSseConnected(false, true);
            attempt = Math.min(attempt + 1, 5);
            const delayMs = Math.min(30_000, 1500 * Math.pow(2, attempt));
            reconnectTimer = setTimeout(connect, delayMs);
          } else {
            setSseConnected(false, false);
          }
        };
      } catch {
        if (!cancelled && useForgeStore.getState().token) {
          setSseConnected(false, true);
          attempt = Math.min(attempt + 1, 5);
          const delayMs = Math.min(30_000, 2000 * Math.pow(2, attempt));
          reconnectTimer = setTimeout(connect, delayMs);
        } else {
          setSseConnected(false, false);
        }
      }
    };

    connect();

    return () => {
      cancelled = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      es?.close();
      setSseConnected(false, false);
    };
  }, [token, setSseConnected, pushPipelineEvent, hydrateOperations, queryClient]);
}
