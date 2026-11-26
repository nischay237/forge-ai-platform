import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { LoginPage } from '../pages/LoginPage';
import { mergeDecisionClasses, severityClasses, statusClasses, toSafeGithubUrl } from '../lib/utils';

describe('FORGE Auth Surface & Security Utilities', () => {
  it('renders login page with empty credentials (no hardcoded demo secrets)', () => {
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>
    );
    expect(screen.getByText(/Sign in to workspace/i)).toBeInTheDocument();
    const emailInput = screen.getByLabelText(/Work Email/i) as HTMLInputElement;
    const passwordInput = screen.getByLabelText(/Password/i) as HTMLInputElement;
    expect(emailInput.value).toBe('');
    expect(passwordInput.value).toBe('');
    expect(screen.queryByDisplayValue('nischay@forge.dev')).not.toBeInTheDocument();
    expect(screen.queryByDisplayValue('ForgeUser@2026')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Sign In/i })).toBeInTheDocument();
  });

  it('sanitizes external GitHub URLs against javascript: and non-GitHub hosts', () => {
    expect(toSafeGithubUrl('https://github.com/nischay237/forge-ai-platform/pull/1')).toBe(
      'https://github.com/nischay237/forge-ai-platform/pull/1'
    );
    expect(toSafeGithubUrl('javascript:alert(1)')).toBeNull();
    expect(toSafeGithubUrl('http://github.com/org/repo')).toBeNull();
    expect(toSafeGithubUrl('https://evil.example.com/github.com/repo')).toBeNull();
    expect(toSafeGithubUrl(null)).toBeNull();
  });

  it('maps merge decision, severity, and status tokens consistently', () => {
    expect(mergeDecisionClasses('BLOCK')).toContain('#111111');
    expect(mergeDecisionClasses('WARN')).toContain('amber');
    expect(mergeDecisionClasses('PASS')).toContain('emerald');
    expect(severityClasses('CRITICAL')).toContain('red');
    expect(severityClasses('HIGH')).toContain('amber');
    expect(statusClasses('COMPLETED')).toContain('emerald');
    expect(statusClasses('FAILED')).toContain('red');
    expect(statusClasses('SUPPRESSED')).toContain('stone');
  });
});
