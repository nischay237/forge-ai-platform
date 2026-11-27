import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ShowcasePage } from '../pages/ShowcasePage';
import { useForgeStore } from '../lib/store';

describe('Public Recruiter Showcase Page (/showcase)', () => {
  beforeEach(() => {
    useForgeStore.setState({ token: null, user: null, tenant: null });
  });

  it('renders public showcase without authentication, labels sample demo as simulated & avoids fabricated metrics', () => {
    render(
      <MemoryRouter initialEntries={['/showcase']}>
        <ShowcasePage />
      </MemoryRouter>
    );

    expect(screen.getByText(/Understand the impact of every code change/i)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Under the Hood' })).toBeInTheDocument();
    expect(screen.getByText(/SIMULATED SAMPLE WALKTHROUGH/i)).toBeInTheDocument();
    expect(screen.getAllByText('Not verified').length).toBeGreaterThanOrEqual(3);
    expect(screen.getAllByText(/not trained ML/i).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText(/Zero private tenant data exposed/i).length).toBeGreaterThanOrEqual(1);
    expect(screen.getByRole('link', { name: 'Engineering & Reliability' })).toHaveAttribute('href', '/engineering');
  });

  it('switches interactive architecture nodes and expands interview walkthrough Q&A', () => {
    render(
      <MemoryRouter initialEntries={['/showcase']}>
        <ShowcasePage />
      </MemoryRouter>
    );

    const outboxTab = screen.getByRole('tab', { name: /Outbox & Kafka Workers/i });
    fireEvent.click(outboxTab);
    expect(outboxTab).toHaveAttribute('aria-selected', 'true');
    expect(screen.getAllByText(/tryClaimEvent/i).length).toBeGreaterThanOrEqual(1);

    fireEvent.click(screen.getByRole('button', { name: /How is multi-tenant isolation enforced/i }));
    expect(screen.getAllByText(/ThreadLocal TenantContext/i).length).toBeGreaterThanOrEqual(1);
  });
});
