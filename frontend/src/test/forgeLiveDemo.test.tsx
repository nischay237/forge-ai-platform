import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ForgeLiveDemoPage } from '../pages/ForgeLiveDemoPage';

describe('FORGE Interactive Live PR Scanner (/forge & /demo)', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('renders calm progressive-disclosure workspace tabs without bombarding all sections at once', () => {
    render(
      <MemoryRouter initialEntries={['/forge']}>
        <ForgeLiveDemoPage />
      </MemoryRouter>
    );

    expect(screen.getByRole('tab', { name: /Live PR Scan/i })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: /Impact Map/i })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Security & CI/i })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Architecture/i })).toBeInTheDocument();

    // Secondary tabs are hidden until explicitly clicked
    expect(screen.queryByText(/Click any service to trace its blast radius/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/DISMISS ALL/i)).not.toBeInTheDocument();

    // Switching to Impact Map reveals the AST graph cleanly
    fireEvent.click(screen.getByRole('tab', { name: /Impact Map/i }));
    expect(screen.getByText(/Click any service to trace its blast radius/i)).toBeInTheDocument();
  });

  it('streams findings one by one with plain numbers (1, 2, 3...) and expands root cause on click', async () => {
    vi.useFakeTimers();
    Element.prototype.scrollIntoView = vi.fn();
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('offline in test — use fallback sample diff'));

    render(
      <MemoryRouter initialEntries={['/forge']}>
        <ForgeLiveDemoPage />
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole('button', { name: /Load & Scan Sample PR/i }));

    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });

    await act(async () => {
      vi.advanceTimersByTime(12_000);
    });

    const liveSection = document.getElementById('live-diagnostics');
    expect(liveSection).not.toBeNull();
    expect(liveSection?.textContent).toContain('Unsafe shared date formatter');
    expect(liveSection?.textContent).toContain('Leaked AWS key found');
    expect(liveSection?.textContent).not.toMatch(/#0[1-9]/);
    expect(screen.queryByText(/DISMISS ALL/i)).not.toBeInTheDocument();

    // Charts are collapsed by default to keep UI calm, and open cleanly on click
    expect(screen.queryByText(/PR Risk Verdict/i)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Risk Score/i }));
    expect(screen.getByText(/PR Risk Verdict/i)).toBeInTheDocument();
  });
});
