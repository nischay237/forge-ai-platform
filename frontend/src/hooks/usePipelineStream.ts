import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useForgeStore } from '../lib/store';
import type { PipelineEvent } from '../lib/types';

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

    const url = `/api/v1/stream/events?token=${encodeURIComponent(token)}`;
    const es = new EventSource(url);

    es.addEventListener('connected', () => {
      setSseConnected(true);
    });

    es.addEventListener('pipeline', (event) => {
      try {
        const parsed = JSON.parse((event as MessageEvent).data) as PipelineEvent;
        pushPipelineEvent(parsed);
        if (parsed.eventType === 'PR_REVIEW') {
          queryClient.invalidateQueries({ queryKey: ['reviews'] });
          queryClient.invalidateQueries({ queryKey: ['review', parsed.resourceId] });
          if (parsed.stage === 'COMPLETED') {
            queryClient.invalidateQueries({ queryKey: ['overview'] });
            queryClient.invalidateQueries({ queryKey: ['security'] });
            queryClient.invalidateQueries({ queryKey: ['audit'] });
          }
        } else if (parsed.eventType === 'REPO_INDEX') {
          queryClient.invalidateQueries({ queryKey: ['repos'] });
          queryClient.invalidateQueries({ queryKey: ['graph', parsed.resourceId] });
          queryClient.invalidateQueries({ queryKey: ['overview'] });
        }
      } catch {
        // ignore malformed frames
      }
    });

    es.onerror = () => {
      setSseConnected(false);
    };

    return () => {
      es.close();
      setSseConnected(false);
    };
  }, [token, setSseConnected, pushPipelineEvent, queryClient]);
}
