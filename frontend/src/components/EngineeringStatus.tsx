import { Link } from 'react-router-dom';
import { ArrowUpRight, ExternalLink } from 'lucide-react';
import {
  formatEvidenceTimestamp,
  useEngineeringEvidence,
  type EngineeringEvidenceSnapshot,
  type EvidenceCheck,
  type EvidenceStatus,
} from '../lib/engineeringEvidence';
import { cn } from '../lib/utils';

const STATUS_META: Record<EvidenceStatus, { label: string; dotClass: string; textClass: string }> = {
  verified: {
    label: 'Verified',
    dotClass: 'bg-emerald-600',
    textClass: 'text-stone-700',
  },
  failed: {
    label: 'Failed',
    dotClass: 'bg-red-600',
    textClass: 'text-red-800',
  },
  stale: {
    label: 'Stale',
    dotClass: 'bg-amber-600',
    textClass: 'text-amber-800',
  },
  unverified: {
    label: 'Not verified',
    dotClass: 'bg-stone-400',
    textClass: 'text-stone-500',
  },
};

export function EvidenceStatusPill({
  status,
  className,
}: {
  status: EvidenceStatus;
  className?: string;
}) {
  const meta = STATUS_META[status];
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full border border-[#E5E5E0] bg-[#FAFAF8] font-mono text-[10.5px] leading-none whitespace-nowrap',
        meta.textClass,
        className
      )}
    >
      <span className={cn('w-1.5 h-1.5 rounded-full shrink-0', meta.dotClass)} aria-hidden="true" />
      <span>{meta.label}</span>
    </span>
  );
}

/**
 * Subtle, compact system-status indicator intended to sit naturally inside an existing
 * section without drawing attention like a promotional banner.
 */
export function SystemStatusIndicator({
  evidence,
  className,
}: {
  evidence?: EngineeringEvidenceSnapshot | null;
  className?: string;
}) {
  const { data, loading } = useEngineeringEvidence(evidence);

  const status: EvidenceStatus = data ? data.overallStatus : 'unverified';
  const meta = STATUS_META[status];
  const label = loading && !data ? 'Checking system status...' : data ? data.overallLabel : 'System status: Not verified';

  return (
    <Link
      to="/engineering"
      aria-label={`${label}. View Engineering and Reliability details.`}
      className={cn(
        'inline-flex items-center gap-2 text-[11px] font-mono text-stone-600 hover:text-[#111111] transition-colors group',
        className
      )}
    >
      <span
        className={cn('w-1.5 h-1.5 rounded-full shrink-0', loading && !data ? 'bg-stone-300' : meta.dotClass)}
        aria-hidden="true"
      />
      <span>{label}</span>
      <ArrowUpRight className="w-3 h-3 text-stone-400 group-hover:text-[#111111] transition-colors" />
    </Link>
  );
}

/**
 * Restrained "Under the Hood" technical summary for the Showcase page.
 * Surfaces real CI, test, security, container, and runtime probe evidence with direct source links.
 */
export function UnderTheHoodSection({
  evidence,
}: {
  evidence?: EngineeringEvidenceSnapshot | null;
}) {
  const { data, loading } = useEngineeringEvidence(evidence);

  const cards: EvidenceCheck[] = data
    ? [
        data.categories.architecture,
        data.categories.cicd,
        data.categories.testing,
        data.categories.security,
        data.categories.deployment,
        data.categories.reliability,
      ]
    : [
        {
          id: 'architecture',
          label: 'Architecture',
          status: 'unverified',
          detail: loading
            ? 'Checking workflow evidence...'
            : 'Modular monolith with ArchUnit boundaries, Transactional Outbox, and PostgreSQL 16 schema.',
          observedAt: null,
          sourceUrl: 'https://github.com/nischay237/forge-ai-platform/blob/main/docs/ARCHITECTURE.md',
          sourceLabel: 'docs/ARCHITECTURE.md',
        },
        {
          id: 'cicd',
          label: 'CI/CD Verification',
          status: 'unverified',
          detail: loading ? 'Checking GitHub Actions status...' : 'GitHub Actions status not verified from this session.',
          observedAt: null,
          sourceUrl: 'https://github.com/nischay237/forge-ai-platform/actions',
          sourceLabel: 'GitHub Actions',
        },
        {
          id: 'testing',
          label: 'Automated Testing',
          status: 'unverified',
          detail: loading ? 'Checking test jobs...' : 'JUnit 5, Testcontainers, and Vitest suites configured in CI; live status not verified.',
          observedAt: null,
          sourceUrl: 'https://github.com/nischay237/forge-ai-platform/blob/main/.github/workflows/ci.yml',
          sourceLabel: 'ci.yml',
        },
        {
          id: 'security',
          label: 'Security Practices',
          status: 'unverified',
          detail: loading ? 'Checking security hygiene job...' : 'AES-256-GCM AAD, rotating tokens, fail-closed SSE tickets, and prod validation.',
          observedAt: null,
          sourceUrl: 'https://github.com/nischay237/forge-ai-platform/blob/main/docs/SECURITY.md',
          sourceLabel: 'docs/SECURITY.md',
        },
        {
          id: 'deployment',
          label: 'Deployment Verification',
          status: 'unverified',
          detail: loading ? 'Checking container build job...' : 'Multi-stage Dockerfiles and readiness probes configured; live build status not verified.',
          observedAt: null,
          sourceUrl: 'https://github.com/nischay237/forge-ai-platform/blob/main/Dockerfile',
          sourceLabel: 'Dockerfile',
        },
        {
          id: 'reliability',
          label: 'Reliability',
          status: 'unverified',
          detail: loading ? 'Checking runtime health probe...' : 'Runtime health probe not verified from this client.',
          observedAt: null,
          sourceUrl: 'https://github.com/nischay237/forge-ai-platform/blob/main/docs/RUNBOOK.md',
          sourceLabel: 'docs/RUNBOOK.md',
        },
      ];

  return (
    <section id="under-the-hood" aria-labelledby="under-the-hood-heading" className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <div className="text-[11px] font-mono uppercase tracking-[0.12em] text-stone-500">
            Operational &amp; Verification Transparency
          </div>
          <h2 id="under-the-hood-heading" className="text-xl font-semibold tracking-[-0.025em] text-[#111111] mt-0.5">
            Under the Hood
          </h2>
        </div>
        <Link
          to="/engineering"
          className="text-xs font-mono text-stone-600 hover:text-[#111111] inline-flex items-center gap-1 underline underline-offset-2"
        >
          Engineering &amp; Reliability ledger <ArrowUpRight className="w-3 h-3" />
        </Link>
      </div>

      <p className="text-xs text-stone-600 max-w-3xl leading-relaxed">
        How FORGE is structured, tested, secured, and verified. Status indicators below reflect live runtime probes and
        GitHub Actions workflow state when reachable; unavailable or unmeasured checks explicitly report{' '}
        <span className="font-mono text-[#111111]">Not verified</span>.
      </p>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {cards.map((card) => (
          <div
            key={card.id}
            className="rounded-xl bg-white border border-[#E5E5E0] p-4 flex flex-col justify-between gap-3"
          >
            <div className="space-y-2">
              <div className="flex items-center justify-between gap-2">
                <h3 className="text-xs font-semibold text-[#111111]">{card.label}</h3>
                <EvidenceStatusPill status={card.status} />
              </div>
              <p className="text-xs text-stone-600 leading-relaxed">{card.detail}</p>
            </div>

            <div className="pt-2.5 border-t border-[#F0F0EC] flex items-center justify-between gap-2 text-[10.5px] font-mono text-stone-500">
              <span className="truncate" title={card.observedAt ?? 'Not verified'}>
                {card.observedAt ? formatEvidenceTimestamp(card.observedAt) : 'Not verified'}
              </span>
              <a
                href={card.sourceUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="text-stone-700 hover:text-[#111111] inline-flex items-center gap-1 shrink-0 underline underline-offset-2"
              >
                {card.sourceLabel}
                <ExternalLink className="w-2.5 h-2.5" />
              </a>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
