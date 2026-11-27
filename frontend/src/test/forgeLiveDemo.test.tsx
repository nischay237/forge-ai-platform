import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ForgeLiveDemoPage } from '../pages/ForgeLiveDemoPage';

describe('FORGE Interactive Live PR Scanner (/forge & /demo)', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('renders standalone Live Detection Stream section without cramped scanner drawer or floating popups', () => {
    render(
      <MemoryRouter initialEntries={['/forge']}>
        <ForgeLiveDemoPage />
      </MemoryRouter>
    );

    expect(screen.getByText(/01 · Incoming GitHub/i)).toBeInTheDocument();
    expect(screen.getByText(/02 · Live Pull Request Scanner/i)).toBeInTheDocument();
    expect(screen.getByText(/03 · Live Detection Stream/i)).toBeInTheDocument();
    expect(screen.getByText(/Issues caught live, one by one\./i)).toBeInTheDocument();
    expect(screen.queryByText(/Diagnostics Output/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/DISMISS ALL/i)).not.toBeInTheDocument();
  });

  it('streams findings one by one with plain numbers (1, 2, 3...) and no #0 prefix or floating toasts', async () => {
    vi.useFakeTimers();
    Element.prototype.scrollIntoView = vi.fn();
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('offline in test — use fallback sample diff'));

    render(
      <MemoryRouter initialEntries={['/forge']}>
        <ForgeLiveDemoPage />
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole('button', { name: /Load Sample PR/i }));
    fireEvent.click(screen.getByRole('button', { name: /Open PR & Start Live Scan/i }));

    // Flush the mocked rejected GitHub fetch promise
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });

    // Advance past full scan timeline so all findings pop in sequentially
    await act(async () => {
      vi.advanceTimersByTime(12_000);
    });

    const liveSection = document.getElementById('live-diagnostics');
    expect(liveSection).not.toBeNull();
    expect(liveSection?.textContent).toContain('Unsafe shared date formatter');
    expect(liveSection?.textContent).toContain('Leaked AWS key found');
    expect(liveSection?.textContent).not.toMatch(/#0[1-9]/);
    expect(screen.queryByText(/DISMISS ALL/i)).not.toBeInTheDocument();
  });
});
