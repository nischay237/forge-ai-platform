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
      return 'bg-stone-100 text-stone-500 border-stone-200';
    default:
      return 'bg-stone-100 text-stone-700 border-stone-200';
  }
}
