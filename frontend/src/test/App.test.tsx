import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { LoginPage } from '../pages/LoginPage';
import { severityClasses, statusClasses } from '../lib/utils';

describe('FORGE UI & Utilities', () => {
  it('renders login workspace craft screen with demo credentials pre-filled', () => {
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>
    );
    expect(screen.getByText(/Sign in to workspace/i)).toBeInTheDocument();
    expect(screen.getByDisplayValue('nischay@forge.dev')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Sign In/i })).toBeInTheDocument();
  });

  it('maps severity and status tokens consistently', () => {
    expect(severityClasses('CRITICAL')).toContain('red');
    expect(severityClasses('HIGH')).toContain('amber');
    expect(statusClasses('COMPLETED')).toContain('emerald');
    expect(statusClasses('FAILED')).toContain('red');
  });
});
