import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { api, ApiClientError } from '../lib/api';
import { queryClient } from '../lib/queryClient';
import { useForgeStore } from '../lib/store';
import { usePipelineStream } from '../hooks/usePipelineStream';
import type { AuthResponse } from '../lib/types';

function makeAuth(tenantId: string, token: string): AuthResponse {
  return {
    accessToken: token,
    refreshToken: 'frt_refresh_' + tenantId,
    tokenType: 'Bearer',
    expiresInSeconds: 900,
    user: {
      id: 'u-' + tenantId,
      tenantId,
      email: `owner@${tenantId}.io`,
      fullName: `Owner ${tenantId}`,
      role: 'OWNER',
      createdAt: new Date().toISOString(),
    },
    tenant: {
      id: tenantId,
      name: `Tenant ${tenantId}`,
      slug: tenantId,
      plan: 'ENTERPRISE',
      aiProvider: 'HYBRID',
      promptVersion: 'forge-review-v2.4',
      requireApprovalBeforePublish: true,
      maskedWebhookSecret: 'whsec_••••1234',
      maskedGithubToken: '',
      githubTokenConfigured: false,
      createdAt: new Date().toISOString(),
    },
  };
}

describe('Multi-Tenant Session Isolation, API Client & Single-Use SSE Tickets', () => {
  beforeEach(() => {
    useForgeStore.getState().clearSession();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    useForgeStore.getState().clearSession();
  });

  it('purges React Query cache and pipelineHistory on clearSession and tenant switch', () => {
    useForgeStore.getState().setSession(makeAuth('tenant-a', 'jwt-a'));
    queryClient.setQueryData(['repos'], [{ id: 'repo-a', fullName: 'a/secret' }]);
    useForgeStore.getState().pushPipelineEvent({
      eventType: 'PR_REVIEW',
      resourceId: 'rev-a',
      stage: 'COMPLETED',
      progressPercent: 100,
      message: 'Tenant A event',
      timestamp: new Date().toISOString(),
    });

    expect(queryClient.getQueryData(['repos'])).toBeDefined();
    expect(useForgeStore.getState().pipelineHistory).toHaveLength(1);

    useForgeStore.getState().clearSession();
    expect(queryClient.getQueryData(['repos'])).toBeUndefined();
    expect(useForgeStore.getState().pipelineHistory).toHaveLength(0);

    // Switching directly from Tenant A -> Tenant B also purges cache
    useForgeStore.getState().setSession(makeAuth('tenant-a', 'jwt-a'));
    queryClient.setQueryData(['overview'], { connectedRepositories: 99 });
    useForgeStore.getState().setSession(makeAuth('tenant-b', 'jwt-b'));
    expect(queryClient.getQueryData(['overview'])).toBeUndefined();
  });

  it('handles non-JSON proxy error responses gracefully without SyntaxError', async () => {
    useForgeStore.getState().setSession(makeAuth('tenant-a', 'jwt-a'));
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response('<html>502 Bad Gateway</html>', {
        status: 502,
        headers: { 'Content-Type': 'text/html' },
      })
    );

    await expect(api.listRepos()).rejects.toMatchObject({
      name: 'Error',
      status: 502,
      code: 'API_ERROR',
    } satisfies Partial<ApiClientError>);
  });

  it('connects SSE via single-use ticket endpoint and never puts JWT in URL', async () => {
    useForgeStore.getState().setSession(makeAuth('tenant-a', 'super-secret-jwt-token'));

    const openedUrls: string[] = [];
    class MockEventSource {
      url: string;
      onerror: (() => void) | null = null;
      constructor(url: string) {
        this.url = url;
        openedUrls.push(url);
      }
      addEventListener() {}
      close() {}
    }
    vi.stubGlobal('EventSource', MockEventSource);
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ ticket: 'sse_single_use_ticket_xyz', expiresInSeconds: 30 }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );

    function Harness() {
      usePipelineStream();
      return null;
    }

    render(
      <QueryClientProvider client={queryClient}>
        <Harness />
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(openedUrls.length).toBe(1);
    });
    expect(openedUrls[0]).toBe('/api/v1/stream/events?ticket=sse_single_use_ticket_xyz');
    expect(openedUrls[0]).not.toContain('super-secret-jwt-token');
    expect(openedUrls[0]).not.toContain('token=');
  });
});
