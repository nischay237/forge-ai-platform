import React from 'react';
import { AlertCircle, RefreshCw } from 'lucide-react';
import { cn } from '../lib/utils';

export function ForgeMark({ className = 'w-6 h-6' }: { className?: string }) {
  return (
    <div
      className={cn(
        'rounded-[5px] bg-[#111111] text-white flex items-center justify-center font-mono font-bold text-xs shrink-0 select-none',
        className
      )}
      aria-hidden="true"
    >
      F
    </div>
  );
}

export function Panel({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        'rounded-xl bg-white border border-[#E5E5E0] shadow-[0_1px_2px_rgba(0,0,0,0.03)]',
        className
      )}
    >
      {children}
    </div>
  );
}

export function Badge({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-[10.5px] font-mono font-medium border tracking-tight',
        className
      )}
    >
      {children}
    </span>
  );
}

export function Button({
  children,
  variant = 'primary',
  size = 'md',
  className,
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  size?: 'sm' | 'md';
}) {
  const variants = {
    primary: 'bg-[#111111] hover:bg-[#27272A] text-white border border-[#111111]',
    secondary: 'bg-white hover:bg-[#F5F5F0] text-[#111111] border border-[#DCDCD5]',
    ghost: 'bg-transparent hover:bg-stone-100 text-stone-600 hover:text-[#111111] border border-transparent',
    danger: 'bg-red-50 hover:bg-red-100 text-red-700 border border-red-200',
  };
  const sizes = {
    sm: 'h-8 px-2.5 text-xs rounded-md',
    md: 'h-9 px-3.5 text-xs font-medium rounded-md',
  };
  return (
    <button
      className={cn(
        'inline-flex items-center justify-center gap-1.5 font-medium transition-colors disabled:opacity-45 disabled:pointer-events-none cursor-pointer',
        variants[variant],
        sizes[size],
        className
      )}
      {...props}
    >
      {children}
    </button>
  );
}

export function PageHeader({
  eyebrow,
  title,
  accentWord,
  description,
  actions,
}: {
  eyebrow?: string;
  title: string;
  accentWord?: string;
  description?: string;
  actions?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col sm:flex-row sm:items-end justify-between gap-4 pb-5 border-b border-[#E5E5E0]">
      <div>
        {eyebrow && (
          <div className="text-[11px] font-mono uppercase tracking-[0.12em] text-stone-500 mb-1">
            {eyebrow}
          </div>
        )}
        <h1 className="text-2xl sm:text-[26px] font-semibold tracking-[-0.03em] text-[#111111] leading-tight">
          {title}{' '}
          {accentWord && (
            <span className="font-editorial italic font-normal text-[1.12em] text-[#111111]">
              {accentWord}
            </span>
          )}
        </h1>
        {description && (
          <p className="text-xs sm:text-[13.5px] text-stone-600 mt-1.5 max-w-2xl leading-relaxed">
            {description}
          </p>
        )}
      </div>
      {actions && <div className="flex items-center gap-2 shrink-0">{actions}</div>}
    </div>
  );
}

export function SkeletonBlock({ className }: { className?: string }) {
  return <div className={cn('animate-pulse rounded-lg bg-stone-200/70', className)} />;
}

export function ErrorBanner({
  message,
  onRetry,
}: {
  message: string;
  onRetry?: () => void;
}) {
  return (
    <div
      role="alert"
      className="rounded-xl bg-red-50 border border-red-200 p-4 flex items-center justify-between gap-4"
    >
      <div className="flex items-center gap-2.5 text-xs text-red-900">
        <AlertCircle className="w-4 h-4 text-red-600 shrink-0" />
        <span>{message}</span>
      </div>
      {onRetry && (
        <Button variant="secondary" size="sm" onClick={onRetry}>
          <RefreshCw className="w-3.5 h-3.5" />
          Retry
        </Button>
      )}
    </div>
  );
}

export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
}: {
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  description: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="rounded-xl border border-dashed border-[#D6D6CE] bg-white py-12 px-6 text-center flex flex-col items-center">
      <div className="w-10 h-10 rounded-lg bg-[#FAFAF8] border border-[#E5E5E0] flex items-center justify-center mb-3">
        <Icon className="w-5 h-5 text-stone-500" />
      </div>
      <h3 className="text-sm font-semibold text-[#111111]">{title}</h3>
      <p className="text-xs text-stone-600 mt-1 max-w-md leading-relaxed">{description}</p>
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function PaginationBar({
  page,
  pageSize,
  totalItems,
  onPageChange,
}: {
  page: number;
  pageSize: number;
  totalItems: number;
  onPageChange: (nextPage: number) => void;
}) {
  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize));
  if (totalItems <= pageSize) {
    return null;
  }
  const safePage = Math.min(Math.max(1, page), totalPages);
  const start = (safePage - 1) * pageSize + 1;
  const end = Math.min(totalItems, safePage * pageSize);

  return (
    <nav
      aria-label="Pagination"
      className="flex flex-wrap items-center justify-between gap-3 pt-2 text-xs font-mono text-stone-600"
    >
      <span>
        Showing {start}–{end} of {totalItems}
      </span>
      <div className="flex items-center gap-2">
        <Button
          type="button"
          size="sm"
          variant="secondary"
          disabled={safePage <= 1}
          onClick={() => onPageChange(safePage - 1)}
        >
          Previous
        </Button>
        <span className="px-2 tabular-nums">
          Page {safePage} / {totalPages}
        </span>
        <Button
          type="button"
          size="sm"
          variant="secondary"
          disabled={safePage >= totalPages}
          onClick={() => onPageChange(safePage + 1)}
        >
          Next
        </Button>
      </div>
    </nav>
  );
}
