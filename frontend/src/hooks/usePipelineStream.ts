import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api } from '../lib/api';
import { useForgeStore } from '../lib/store';
import type { PipelineEvent } from '../lib/types';

/**
 * Connects to FORGE's live SSE pipeline using a short-lived, single-use ticket obtained via
 * authenticated POST /api/v1/stream/ticket so long-lived JWTs never appear in query URLs.
 */
export function usePipelineStream() {
  const token = useForgeStore((s) => s.token);
  const setSseConnected = useForgeStore((s) => s.setSseConnected);
  const pushPipelineEvent = useForgeStore((s) => s.pushPipelineEvent);
  const queryClient = useQueryClient();

  useEffect(() => {
    if (!token) {
      setSseConnected(false);
      return;
    }

    let cancelled = false;
    let es: EventSource | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let attempt = 0;

    const connect = async () => {
      if (cancelled || !useForgeStore.getState().token) return;
      try {
        const { ticket } = await api.issueSseTicket();
        if (cancelled) return;

        const url = `/api/v1/stream/events?ticket=${encodeURIComponent(ticket)}`;
        es = new EventSource(url);

        es.addEventListener('connected', () => {
          attempt = 0;
          setSseConnected(true);
        });

        es.addEventListener('pipeline', (event) => {
          try {
            const parsed = JSON.parse((event as MessageEvent).data) as PipelineEvent;
            pushPipelineEvent(parsed);
            if (parsed.eventType === 'PR_REVIEW') {
              queryClient.invalidateQueries({ queryKey: ['reviews'] });
              queryClient.invalidateQueries({ queryKey: ['review', parsed.resourceId] });
              if (parsed.stage === 'COMPLETED' || parsed.stage === 'FAILED') {
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
            }
          } catch {
            // ignore malformed SSE frames
          }
        });

        es.onerror = () => {
          setSseConnected(false);
          es?.close();
          es = null;
          if (!cancelled && useForgeStore.getState().token) {
            attempt = Math.min(attempt + 1, 5);
            const delayMs = Math.min(30_000, 1500 * Math.pow(2, attempt));
            reconnectTimer = setTimeout(connect, delayMs);
          }
        };
      } catch {
        setSseConnected(false);
        if (!cancelled && useForgeStore.getState().token) {
          attempt = Math.min(attempt + 1, 5);
          const delayMs = Math.min(30_000, 2000 * Math.pow(2, attempt));
          reconnectTimer = setTimeout(connect, delayMs);
        }
      }
    };

    connect();

    return () => {
      cancelled = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      es?.close();
      setSseConnected(false);
    };
  }, [token, setSseConnected, pushPipelineEvent, queryClient]);
}
