import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatRelativeTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const diffSec = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 1000));
  if (diffSec < 60) return `${diffSec}s ago`;
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)}m ago`;
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}h ago`;
  return `${Math.floor(diffSec / 86400)}d ago`;
}

/**
 * Validates external GitHub URLs so untrusted payloads can never inject javascript: or data: hrefs.
 */
export function toSafeGithubUrl(raw: string | null | undefined): string | null {
  if (!raw) return null;
  try {
    const parsed = new URL(raw);
    if (parsed.protocol === 'https:' && (parsed.hostname === 'github.com' || parsed.hostname === 'www.github.com')) {
      return parsed.toString();
    }
    return null;
  } catch {
    return null;
  }
}

export function severityClasses(severity: string): string {
  switch (severity?.toUpperCase()) {
    case 'CRITICAL':
      return 'bg-red-50 text-red-700 border-red-200';
    case 'HIGH':
      return 'bg-amber-50 text-amber-800 border-amber-200';
    case 'MEDIUM':
      return 'bg-sky-50 text-sky-800 border-sky-200';
    case 'LOW':
      return 'bg-stone-100 text-stone-700 border-stone-200';
    default:
      return 'bg-stone-100 text-stone-700 border-stone-200';
  }
}

export function mergeDecisionClasses(decision: string | null | undefined): string {
  switch (decision?.toUpperCase()) {
    case 'BLOCK':
      return 'bg-[#111111] text-white border-[#111111] font-bold';
    case 'WARN':
      return 'bg-amber-50 text-amber-900 border-amber-300 font-semibold';
    case 'PASS':
      return 'bg-emerald-50 text-emerald-800 border-emerald-300 font-semibold';
    default:
      return 'bg-stone-100 text-stone-700 border-stone-200';
  }
}

export function predictionRiskClasses(level: string | null | undefined): string {
  switch (level?.toUpperCase()) {
    case 'CRITICAL':
      return 'bg-[#111111] text-white border-[#111111] font-semibold';
    case 'HIGH':
      return 'bg-red-50 text-red-800 border-red-200 font-semibold';
    case 'MEDIUM':
      return 'bg-amber-50 text-amber-900 border-amber-200 font-medium';
    case 'LOW':
      return 'bg-emerald-50 text-emerald-800 border-emerald-200 font-medium';
    case 'INSUFFICIENT_DATA':
      return 'bg-stone-100 text-stone-600 border-stone-300 font-mono';
    default:
      return 'bg-stone-100 text-stone-700 border-stone-200';
  }
}

export function statusClasses(status: string): string {
  switch (status?.toUpperCase()) {
    case 'COMPLETED':
    case 'PASSED':
    case 'INDEXED':
    case 'APPROVED':
    case 'MITIGATED':
    case 'TRUE_POSITIVE':
    case 'TRUE_NEGATIVE':
      return 'bg-emerald-50 text-emerald-800 border-emerald-200';
    case 'FAILED':
    case 'INDEX_FAILED':
    case 'OPEN':
    case 'FALSE_NEGATIVE':
    case 'FALSE_POSITIVE':
      return 'bg-red-50 text-red-700 border-red-200';
    case 'RUNNING':
    case 'INDEXING':
    case 'QUEUED':
    case 'PENDING':
      return 'bg-stone-900 text-white border-stone-900';
    case 'DISMISSED':
    case 'SUPPRESSED':
      return 'bg-stone-100 text-stone-500 border-stone-200';
    default:
      return 'bg-stone-100 text-stone-700 border-stone-200';
  }
}

export function downloadJsonFile(filename: string, payload: unknown): void {
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
