import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Code2,
  Cpu,
  Database,
  ExternalLink,
  FileCode2,
  GitCompare,
  Layers,
  Lock,
  Network,
  Play,
  Radar,
  RotateCcw,
  Scale,
  Server,
  ShieldCheck,
  Sparkles,
  Terminal,
} from 'lucide-react';
import { useForgeStore } from '../lib/store';
import { LiveProcessingPanel } from '../components/LiveProcessingPanel';
import { Badge, Button, ForgeMark, Panel } from '../components/ui';
import { cn } from '../lib/utils';

const GITHUB_BASE = 'https://github.com/nischay237/forge-ai-platform/blob/main';

interface ArchNode {
  id: string;
  label: string;
  layer: string;
  summary: string;
  details: string[];
  failureMode: string;
  sourcePath: string;
}

const ARCH_NODES: ArchNode[] = [
  {
    id: 'api-auth',
    label: 'REST API, Auth & RBAC',
    layer: 'Edge / Identity',
    summary: 'Stateless HS256 JWT auth, rotating refresh tokens (PESSIMISTIC_WRITE), brute-force lockout, correlation IDs, and strict per-tenant isolation.',
    details: [
      'Every endpoint binds TenantContext from verified JWT claims — never trusts client-supplied tenant IDs.',
      'Concurrent refresh-token rotation guarded by database PESSIMISTIC_WRITE lock + reuse family revocation.',
      'Per-tenant CSPRNG GitHub webhook secrets verified via constant-time HMAC-SHA256 with 1 MiB body cap (HTTP 413).',
    ],
    failureMode: 'Invalid/reused refresh token revokes the entire token family immediately (HTTP 401) and writes an immutable audit log.',
    sourcePath: 'backend/src/main/java/com/forge/platform/identity/SecurityConfig.java',
  },
  {
    id: 'ast-codeintel',
    label: 'AST & Dependency Graph',
    layer: 'Code Intelligence',
    summary: 'Java 21 JavaParser AST + TypeScript/JavaScript structural parser computing disambiguated dependency edges, DFS cycles, and BFS blast radius.',
    details: [
      'Extracts classes, records, enums, overloaded method signatures, cyclomatic complexity, and AST call/type references.',
      'Disambiguates duplicate basenames across packages (e.g., billing/Config.java vs auth/Config.java).',
      'Computes direct + transitive reverse-dependency impact chains and flags layer architecture drift violations.',
    ],
    failureMode: 'Unsupported languages (e.g., Python/Go) degrade cleanly to diff-level heuristics and are explicitly flagged in limitations.',
    sourcePath: 'backend/src/main/java/com/forge/platform/codeintel/CodeIntelligenceService.java',
  },
  {
    id: 'review-policy',
    label: 'Deterministic Policy Engine',
    layer: 'Change Risk & Security',
    summary: 'Evaluates OpenAPI/Spring API breaks, Flyway destructive SQL DDL, secrets/Shannon entropy, SAST, IaC, offline SCA, and workspace Policy-as-Code.',
    details: [
      'Merge verdicts (PASS / WARN / BLOCK) are 100% deterministic and explainable — LLMs never independently invent merge blocks.',
      'Generates CycloneDX 1.5 SBOM JSON and OASIS SARIF 2.1.0 exports with persistent fingerprint suppressions.',
      'Static Test Selection recommends impacted unit/integration tests and explicitly labels them NOT_EXECUTED.',
    ],
    failureMode: 'If OpenAI is unconfigured or times out under Resilience4j circuit breaker, reviews complete deterministically without failing.',
    sourcePath: 'backend/src/main/java/com/forge/platform/review/ChangeRiskEngine.java',
  },
  {
    id: 'prediction',
    label: 'Predictive Risk Baselines',
    layer: 'Predictive Intelligence',
    summary: '5 deterministic baseline analyzers + 7-channel deduplicated risk aggregator over real historical defects, CI runs, deployments, and incidents.',
    details: [
      'Bug Risk, Test Failure Risk, Production Incident Risk, Deployment/Rollback Risk, and Static Performance Warnings.',
      'Uncalibrated probabilities are intentionally omitted (null); insufficient historical data is surfaced transparently.',
      'Chronological held-out evaluation uses earliest pre-outcome runs and excludes pre-prediction outcomes to prevent temporal leakage.',
    ],
    failureMode: 'Predictions are strictly advisory and never automatically block a PR; non-code docs/assets are excluded from code penalties.',
    sourcePath: 'backend/src/main/java/com/forge/platform/prediction/PredictionPipelineService.java',
  },
  {
    id: 'outbox-kafka',
    label: 'Outbox & Kafka Workers',
    layer: 'Async Reliability',
    summary: 'Transactional Outbox with atomic SQL row-claiming (tryClaimEvent), worker leases, two-phase JDBC idempotency, and Kafka DLT routing.',
    details: [
      'Domain rows and outbox events commit in a single ACID PostgreSQL transaction — zero dual-write drift.',
      'Kafka dispatch runs outside DB transactions to prevent HikariCP pool exhaustion under broker latency.',
      '@RetryableTopic retries transient failures 3x with exponential backoff before routing to *.dlt + audit log.',
    ],
    failureMode: 'Crashed worker leases expire automatically (locked_until); dead-letter rows can be requeued per tenant after recovery.',
    sourcePath: 'backend/src/main/java/com/forge/platform/messaging/OutboxService.java',
  },
  {
    id: 'sse-redis',
    label: 'SSE Hub, Redis & Storage',
    layer: 'Real-Time & Persistence',
    summary: 'Single-use fail-closed SSE tickets, monotonic event sequencing, DB-backed restart recovery, and Flyway V1–V6 PostgreSQL 16 schema.',
    details: [
      'Browser exchanges Bearer JWT for a 30s single-use SSE ticket (POST /api/v1/stream/ticket) — JWTs never appear in URLs.',
      'SseHubService pairs a per-tenant ring buffer with tenant-scoped OperationStatusFallbackProvider DB recovery across restarts.',
      'PostgreSQL 16 schema managed via Flyway V1–V6 with composite tenant indexes and optional Row-Level Security SQL.',
    ],
    failureMode: 'During Redis outages, cache calls degrade safely to PostgreSQL while SSE ticket redemption fails closed against replay.',
    sourcePath: 'backend/src/main/java/com/forge/platform/sse/SseHubService.java',
  },
];

interface SimulatedStage {
  stage: string;
  label: string;
  message: string;
  details: Record<string, string | number>;
}

const SIMULATED_PR_STAGES: SimulatedStage[] = [
  {
    stage: 'QUEUED',
    label: 'Request accepted & queued',
    message: '[SIMULATED] Accepted PR #418 (feat/settlement-batch-v2) via Transactional Outbox',
    details: { prNumber: 418, transport: 'outbox', mode: 'SIMULATED_SAMPLE' },
  },
  {
    stage: 'INGESTING_DIFF',
    label: 'Parsing unified diff hunks',
    message: '[SIMULATED] Parsed 2 changed files (SettlementService.java, V7__drop_legacy_audit.sql)',
    details: { changedFiles: 2, addedLines: 29, removedLines: 6 },
  },
  {
    stage: 'AST_IMPACT_ANALYSIS',
    label: 'Computing AST dependency blast radius',
    message: '[SIMULATED] Resolved 2 direct & 4 transitive dependents across payments & ledger modules',
    details: { directDependents: 2, transitiveDependents: 4, cyclesDetected: 0 },
  },
  {
    stage: 'CONTRACT_AND_MIGRATION_ANALYSIS',
    label: 'Checking REST API & SQL migration safety',
    message: '[SIMULATED] Flagged destructive DDL: DROP COLUMN legacy_tx_ref in V7__drop_legacy_audit.sql',
    details: { breakingApi: 0, destructiveMigrations: 1 },
  },
  {
    stage: 'SECURITY_SCANNING',
    label: 'Scanning secrets, SAST & prompt-injection patterns',
    message: '[SIMULATED] Detected SQL string concatenation risk (SAST-SQLI-001) in SettlementService.java:42',
    details: { criticalFindings: 1, highFindings: 0, suppressed: 0 },
  },
  {
    stage: 'AI_CONTEXT_REVIEW',
    label: 'Synthesizing contextual review findings',
    message: '[SIMULATED] Deterministic baseline enriched with remediation guidance (expand-and-contract DDL + parameterized query)',
    details: { providerMode: 'DETERMINISTIC', promptInjection: 'NONE' },
  },
  {
    stage: 'RISK_AND_TEST_SELECTION',
    label: 'Evaluating policy merge decision & impacted tests',
    message: '[SIMULATED] Policy gate triggered BLOCK (destructive SQL migration + critical SQLi); selected 3 tests (NOT_EXECUTED)',
    details: { mergeDecision: 'BLOCK', riskScore: 84, selectedTests: '3 (NOT_EXECUTED)' },
  },
  {
    stage: 'COMPLETED',
    label: 'Review complete & persisted',
    message: '[SIMULATED] Persisted findings, risk breakdown & enqueued async predictive baseline (forge.pr-prediction.requested)',
    details: { status: 'COMPLETED', predictiveAdvisory: 'HIGH (Static N+1 + Destructive DDL)' },
  },
];

const SIMULATED_DIFF_SAMPLE = `diff --git a/src/main/java/com/acme/payments/SettlementService.java b/src/main/java/com/acme/payments/SettlementService.java
--- a/src/main/java/com/acme/payments/SettlementService.java
+++ b/src/main/java/com/acme/payments/SettlementService.java
@@ -34,4 +34,11 @@ public class SettlementService {
     public void settleBatch(List<String> txIds) {
+        for (String txId : txIds) {
+            // Static perf warning: DB lookup inside loop (N+1)
+            Settlement row = settlementRepository.findById(txId).orElseThrow();
+            // Critical SAST warning: unsanitized SQL concatenation
+            jdbcTemplate.execute("UPDATE settlements SET status='PAID' WHERE id='" + txId + "'");
+        }
     }
 }
diff --git a/src/main/resources/db/migration/V7__drop_legacy_audit.sql b/src/main/resources/db/migration/V7__drop_legacy_audit.sql
--- /dev/null
+++ b/src/main/resources/db/migration/V7__drop_legacy_audit.sql
@@ -0,0 +1,2 @@
+-- Destructive DDL warning: non-reversible column drop breaks rolling deploys
+ALTER TABLE settlements DROP COLUMN legacy_tx_ref;`;

const TRADE_OFFS = [
  {
    decision: 'Modular Monolith (ArchUnit-enforced) over Microservices',
    rationale: 'Keeps ACID transactions between domain tables and outbox_events simple and debuggable while enforcing strict package boundaries (common, audit, codeintel, review, prediction) at compile/test time via ArchUnit.',
    tradeOff: 'Independent per-module scaling requires extracting Kafka consumers later; mitigated by clean topic contracts.',
    sourcePath: 'docs/adr/ADR-001-modular-monolith.md',
  },
  {
    decision: 'Deterministic Policy-as-Code + Baselines over Black-Box LLM Decisions',
    rationale: 'Merge verdicts (PASS/WARN/BLOCK) and predictive baselines stay reproducible, auditable, and functional with zero external API keys. AI enriches explanations under Resilience4j timeouts and prompt-injection quarantine.',
    tradeOff: 'Rule-based baselines miss subtle semantic bugs compared to deep runtime profiling or trained models on large historical datasets.',
    sourcePath: 'backend/src/main/java/com/forge/platform/prediction/EngineeringRiskAggregator.java',
  },
  {
    decision: 'Single-Use SSE Tickets + DB Fallback over JWT-in-Query-Params',
    rationale: 'Native browser EventSource cannot attach custom Authorization headers; putting JWTs in URLs leaks credentials into proxy/access logs. FORGE mints 30s single-use tickets (fail-closed in Redis) and recovers operation state from PostgreSQL across restarts.',
    tradeOff: 'Adds one lightweight POST /api/v1/stream/ticket handshake before connecting EventSource.',
    sourcePath: 'backend/src/main/java/com/forge/platform/sse/SseController.java',
  },
  {
    decision: 'Transactional Outbox with Atomic Row Claiming over Direct Kafka Publish',
    rationale: 'Prevents lost events when DB commits succeed but Kafka is momentarily unavailable, and prevents duplicate claims across horizontal replicas via conditional SQL updates (tryClaimEvent).',
    tradeOff: 'Adds polling latency (~800ms local) compared to synchronous in-request dispatch.',
    sourcePath: 'backend/src/main/java/com/forge/platform/messaging/OutboxEventRepository.java',
  },
];

const INTERVIEW_QA = [
  {
    q: 'How does FORGE prevent dual-write bugs between PostgreSQL and Kafka?',
    a: 'All async workflows (PR reviews, repository AST indexing, predictive baselines) write their domain entity and an outbox_events row in the same ACID database transaction. OutboxService polls pending rows outside long-held DB transactions using an atomic conditional SQL update (tryClaimEvent with locked_by/locked_until lease) so multiple backend replicas never double-claim, then publishes to Kafka where consumers apply two-phase JDBC idempotency (processed_events IN_PROGRESS -> COMPLETED).',
    sourcePath: 'backend/src/main/java/com/forge/platform/messaging/OutboxService.java',
  },
  {
    q: 'How is multi-tenant isolation enforced end-to-end?',
    a: 'JwtAuthenticationFilter derives tenantId exclusively from verified HS256 JWT claims and stores it in ThreadLocal TenantContext (cleared in finally). Every repository query scopes by tenantId, ArchUnit explicitly forbids unscoped SourceFileJpaRepository.findById calls, secrets are AES-256-GCM encrypted with tenant+purpose AAD, webhook endpoints resolve per-tenant HMAC secrets by slug, and optional PostgreSQL 16 Row-Level Security policies are provided in docs/postgres-rls.sql.',
    sourcePath: 'docs/SECURITY.md',
  },
  {
    q: 'Why do you claim deterministic baselines instead of trained ML models?',
    a: 'Training a supervised classifier without sufficient real, post-prediction labelled outcomes produces overfitted or fabricated accuracy numbers. FORGE ships inspectable deterministic baselines (DETERMINISTIC_BASELINE_V1), omits uncalibrated probabilities (null), separates predicted test risk from actual CI outcomes, and runs chronological held-out evaluation (earliest pre-outcome run per PR, excluding pre-prediction outcomes) that reports INSUFFICIENT_LABELLED_DATA until at least 5 real matched outcomes exist.',
    sourcePath: 'backend/src/main/java/com/forge/platform/prediction/PredictionEvaluationService.java',
  },
  {
    q: 'How does the real-time SSE timeline behave on refresh, reconnect, or server restart?',
    a: 'Every backend stage publishes a structured PipelineEvent with eventId, operationId, correlationId, and monotonic sequence. The frontend deduplicates by eventId, ignores out-of-order regressions, and refuses to reopen terminal COMPLETED/FAILED states. On browser refresh or SSE reconnect, LiveProcessingPanel hydrates GET /api/v1/stream/operations — and if the in-memory ring buffer was cleared by a process restart, SseHubService queries tenant-scoped OperationStatusFallbackProvider beans backed by PostgreSQL.',
    sourcePath: 'backend/src/main/java/com/forge/platform/sse/OperationStatusFallbackProvider.java',
  },
  {
    q: 'How do you defend against prompt injection when reviewing untrusted PR diffs?',
    a: 'PromptInjectionDetector scans diffs for prompt-override directives (FORGE-PROMPT-001) and can trigger a deterministic Policy-as-Code BLOCK. When LLM enrichment is enabled, AiReviewEngine separates SystemMessage instructions from quarantined UserMessage diff content and constrains output to structured non-blocking commentary — deterministic rules always own the merge decision.',
    sourcePath: 'backend/src/main/java/com/forge/platform/review/AiReviewEngine.java',
  },
];

const SOURCE_LINKS = [
  { label: 'PR Review Pipeline (8 SSE stages)', path: 'backend/src/main/java/com/forge/platform/review/ReviewPipelineService.java' },
  { label: 'Java 21 AST & Dependency Graph', path: 'backend/src/main/java/com/forge/platform/codeintel/CodeIntelligenceService.java' },
  { label: 'Predictive Pipeline & 7-Channel Aggregator', path: 'backend/src/main/java/com/forge/platform/prediction/EngineeringRiskAggregator.java' },
  { label: 'Anti-Leakage Chronological Evaluation', path: 'backend/src/main/java/com/forge/platform/prediction/PredictionEvaluationService.java' },
  { label: 'Security Scanner, SBOM & SARIF 2.1.0', path: 'backend/src/main/java/com/forge/platform/security/SecurityService.java' },
  { label: 'Transactional Outbox & Atomic Claiming', path: 'backend/src/main/java/com/forge/platform/messaging/OutboxService.java' },
  { label: 'SSE Hub & DB-Backed Restart Recovery', path: 'backend/src/main/java/com/forge/platform/sse/SseHubService.java' },
  { label: 'Production Security Fail-Fast Validator', path: 'backend/src/main/java/com/forge/platform/common/ProductionSecurityValidator.java' },
  { label: 'PostgreSQL 16 Flyway V6 Predictive Schema', path: 'backend/src/main/resources/db/migration/V6__predictive_engineering_intelligence.sql' },
  { label: 'Live Dark-First SSE Timeline Component', path: 'frontend/src/components/LiveProcessingPanel.tsx' },
  { label: 'GitHub Actions CI (4-Job Pipeline)', path: '.github/workflows/ci.yml' },
  { label: 'ArchUnit Module Boundary Suite', path: 'backend/src/test/java/com/forge/platform/ArchitectureBoundaryTest.java' },
];

export function ShowcasePage() {
  const token = useForgeStore((s) => s.token);
  const [selectedNodeId, setSelectedNodeId] = useState<string>(ARCH_NODES[0].id);
  const [simStep, setSimStep] = useState<number>(SIMULATED_PR_STAGES.length - 1);
  const [simPlaying, setSimPlaying] = useState(false);
  const [openQaIndex, setOpenQaIndex] = useState<number | null>(0);

  const selectedNode = ARCH_NODES.find((n) => n.id === selectedNodeId) ?? ARCH_NODES[0];

  useEffect(() => {
    if (!simPlaying) return;
    if (simStep >= SIMULATED_PR_STAGES.length - 1) {
      setSimPlaying(false);
      return;
    }
    const timer = window.setTimeout(() => {
      setSimStep((prev) => Math.min(SIMULATED_PR_STAGES.length - 1, prev + 1));
    }, 650);
    return () => window.clearTimeout(timer);
  }, [simPlaying, simStep]);

  const startSimulatedReplay = () => {
    setSimStep(0);
    setSimPlaying(true);
  };

  return (
    <div className="min-h-screen bg-[#FAFAF8] text-[#111111]">
      {/* Sticky Top Header */}
      <header className="sticky top-0 z-30 bg-white/95 backdrop-blur-md border-b border-[#E5E5E0]">
        <div className="max-w-[1240px] mx-auto px-5 sm:px-8 h-14 flex items-center justify-between gap-4">
          <div className="flex items-center gap-2.5">
            <ForgeMark className="w-6 h-6" />
            <span className="font-semibold text-[15px] tracking-[-0.03em]">Forge</span>
            <Badge className="bg-stone-100 text-stone-700 border-stone-200 hidden sm:inline-flex">
              Engineering Showcase
            </Badge>
          </div>
          <nav aria-label="Showcase navigation" className="hidden md:flex items-center gap-5 text-xs text-stone-600">
            <a href="#architecture" className="hover:text-[#111111]">Architecture</a>
            <a href="#demo" className="hover:text-[#111111]">PR Demo</a>
            <a href="#tradeoffs" className="hover:text-[#111111]">Trade-Offs</a>
            <a href="#evidence" className="hover:text-[#111111]">Verification</a>
            <a href="#walkthrough" className="hover:text-[#111111]">Interview Q&amp;A</a>
            <a href="#limitations" className="hover:text-[#111111]">Limitations</a>
          </nav>
          <div className="flex items-center gap-2">
            <a
              href="https://github.com/nischay237/forge-ai-platform"
              target="_blank"
              rel="noopener noreferrer"
              className="h-8 px-3 rounded-md border border-[#DCDCD5] bg-white hover:bg-[#F5F5F0] text-xs font-medium text-[#111111] inline-flex items-center gap-1.5"
            >
              GitHub
              <ExternalLink className="w-3 h-3" />
            </a>
            <Link
              to={token ? '/' : '/login'}
              className="h-8 px-3 rounded-md bg-[#111111] hover:bg-[#27272A] text-white text-xs font-medium inline-flex items-center gap-1.5"
            >
              {token ? 'Open Workspace' : 'Sign In / Register'}
              <ArrowRight className="w-3 h-3" />
            </Link>
          </div>
        </div>
      </header>

      <main className="max-w-[1240px] mx-auto px-5 sm:px-8 py-10 space-y-12">
        {/* 1. 10-Second Hero Introduction */}
        <section className="rounded-2xl bg-white border border-[#E5E5E0] p-7 sm:p-10 shadow-[0_1px_3px_rgba(0,0,0,0.03)] space-y-6">
          <div className="flex flex-wrap items-center gap-2 text-[11px] font-mono uppercase tracking-[0.12em] text-stone-500">
            <span className="w-2 h-2 rounded-full bg-emerald-600" />
            Public Technical Showcase · Zero Private Tenant Data Exposed
          </div>

          <div className="max-w-4xl space-y-4">
            <h1 className="text-3xl sm:text-[42px] font-semibold tracking-[-0.035em] leading-[1.08] text-[#111111]">
              Understand the impact of every code change.{' '}
              <span className="font-editorial italic font-normal">
                Predict what could fail before production.
              </span>
            </h1>
            <p className="text-sm sm:text-[15px] text-stone-600 leading-relaxed max-w-3xl">
              <strong>FORGE</strong> is a multi-tenant software engineering intelligence platform built in{' '}
              <strong>Java 21 + Spring Boot 3.5 + PostgreSQL 16 + Kafka + Redis + React 18</strong>. It combines
              Java/TypeScript AST dependency impact graphs, OpenAPI &amp; SQL migration safety checks, secret/SAST/SCA
              scanning, Policy-as-Code merge gates (<code className="font-mono text-xs">PASS/WARN/BLOCK</code>), and
              explainable deterministic failure-risk baselines streamed live over SSE.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-3 pt-1">
            <a
              href="#demo"
              className="h-9 px-4 rounded-md bg-[#111111] hover:bg-[#27272A] text-white text-xs font-medium inline-flex items-center gap-1.5"
            >
              <Play className="w-3.5 h-3.5" />
              Interactive PR Analysis Demo
            </a>
            <a
              href="#architecture"
              className="h-9 px-4 rounded-md bg-white hover:bg-[#F5F5F0] border border-[#DCDCD5] text-[#111111] text-xs font-medium inline-flex items-center gap-1.5"
            >
              <Network className="w-3.5 h-3.5" />
              Explore Interactive Architecture
            </a>
            <Link
              to={token ? '/reviews?new=1' : '/login'}
              className="h-9 px-4 rounded-md bg-white hover:bg-[#F5F5F0] border border-[#DCDCD5] text-[#111111] text-xs font-medium inline-flex items-center gap-1.5"
            >
              {token ? 'Run Real PR Review (Live SSE)' : 'Provision Local / Live Workspace'}
              <ArrowRight className="w-3.5 h-3.5" />
            </Link>
          </div>

          {/* 3-Column Problem / Solution / Truth Boundary */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 pt-4 border-t border-[#EFEFEA]">
            <div className="p-4 rounded-xl bg-[#FAFAF8] border border-[#EAEAE4] space-y-1.5">
              <div className="text-[10.5px] font-mono uppercase tracking-[0.1em] text-red-700 font-semibold">
                The Real Engineering Problem
              </div>
              <p className="text-xs text-stone-700 leading-relaxed">
                Line-by-line PR diffs hide downstream dependency blast radius, breaking REST/SQL contracts, N+1 loops,
                and rollback risk. Generic LLM wrappers hallucinate severities and cannot own deterministic merge gates.
              </p>
            </div>
            <div className="p-4 rounded-xl bg-[#FAFAF8] border border-[#EAEAE4] space-y-1.5">
              <div className="text-[10.5px] font-mono uppercase tracking-[0.1em] text-emerald-700 font-semibold">
                How FORGE Solves It
              </div>
              <p className="text-xs text-stone-700 leading-relaxed">
                Deterministic static analyzers (AST, API/DDL compat, security/SCA) own <code className="font-mono">PASS/WARN/BLOCK</code>{' '}
                policy decisions with exact file/line evidence, while advisory predictive baselines score defect, test,
                incident, and rollback risk over real persisted history.
              </p>
            </div>
            <div className="p-4 rounded-xl bg-[#FAFAF8] border border-[#EAEAE4] space-y-1.5">
              <div className="text-[10.5px] font-mono uppercase tracking-[0.1em] text-[#111111] font-semibold">
                Strict Honesty &amp; Truth Boundary
              </div>
              <p className="text-xs text-stone-700 leading-relaxed">
                No fabricated ML accuracy, no uncalibrated probabilities, no fake progress percentages, and no claims
                that unexecuted tests passed. Where external telemetry or coverage metrics are unavailable, FORGE states{' '}
                <strong>Not verified</strong> or <strong>INSUFFICIENT_DATA</strong>.
              </p>
            </div>
          </div>
        </section>

        {/* 2. Interactive Architecture Diagram */}
        <section id="architecture" className="space-y-4">
          <div className="flex flex-wrap items-end justify-between gap-2">
            <div>
              <div className="text-[11px] font-mono uppercase tracking-[0.12em] text-stone-500">
                Interactive System Architecture (Click Any Subsystem)
              </div>
              <h2 className="text-xl font-semibold tracking-[-0.025em] text-[#111111] mt-0.5">
                How FORGE Processes a Code Change End-to-End
              </h2>
            </div>
            <a
              href={`${GITHUB_BASE}/docs/ARCHITECTURE.md`}
              target="_blank"
              rel="noopener noreferrer"
              className="text-xs font-mono text-stone-600 hover:text-[#111111] inline-flex items-center gap-1 underline underline-offset-2"
            >
              docs/ARCHITECTURE.md <ExternalLink className="w-3 h-3" />
            </a>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-12 gap-5">
            {/* Interactive Flow Grid */}
            <div className="lg:col-span-7 rounded-xl bg-[#0D0E12] border border-[#23262F] p-5 text-zinc-100 space-y-4">
              <div className="flex items-center justify-between text-[11px] font-mono text-zinc-400">
                <span>MODULAR MONOLITH · ARCHUNIT-ENFORCED BOUNDARIES</span>
                <span className="text-emerald-400">PostgreSQL 16 · Kafka · Redis</span>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3" role="tablist" aria-label="Architecture subsystems">
                {ARCH_NODES.map((node, idx) => {
                  const active = node.id === selectedNode.id;
                  return (
                    <button
                      key={node.id}
                      type="button"
                      role="tab"
                      aria-selected={active}
                      onClick={() => setSelectedNodeId(node.id)}
                      className={cn(
                        'text-left p-3.5 rounded-lg border transition-all cursor-pointer',
                        active
                          ? 'bg-indigo-500/15 border-indigo-400 text-white shadow-[0_0_20px_rgba(99,102,241,0.18)]'
                          : 'bg-[#13161E] border-[#252936] text-zinc-300 hover:border-zinc-500'
                      )}
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className="text-[10px] font-mono uppercase tracking-[0.08em] text-zinc-400">
                          0{idx + 1} · {node.layer}
                        </span>
                        {active && <span className="w-2 h-2 rounded-full bg-indigo-400" />}
                      </div>
                      <div className="text-xs font-semibold mt-1">{node.label}</div>
                      <p className="text-[11px] text-zinc-400 mt-1 line-clamp-2 leading-relaxed">
                        {node.summary}
                      </p>
                    </button>
                  );
                })}
              </div>

              <div className="pt-3 border-t border-[#1E222D] text-[11px] font-mono text-zinc-400 flex flex-wrap items-center gap-2">
                <span className="px-2 py-0.5 rounded bg-[#161923] border border-[#282D3B]">PR Webhook / API</span>
                <span>→</span>
                <span className="px-2 py-0.5 rounded bg-[#161923] border border-[#282D3B]">Outbox ACID Tx</span>
                <span>→</span>
                <span className="px-2 py-0.5 rounded bg-[#161923] border border-[#282D3B]">AST + Security + Policy</span>
                <span>→</span>
                <span className="px-2 py-0.5 rounded bg-[#161923] border border-[#282D3B]">Predictive Baselines</span>
                <span>→</span>
                <span className="px-2 py-0.5 rounded bg-[#161923] border border-[#282D3B]">Live SSE Timeline</span>
              </div>
            </div>

            {/* Selected Subsystem Deep-Dive Inspector */}
            <Panel className="lg:col-span-5 p-5 flex flex-col justify-between space-y-4">
              <div className="space-y-3">
                <div className="flex items-center justify-between gap-2">
                  <Badge className="bg-stone-100 text-stone-700 border-stone-200">{selectedNode.layer}</Badge>
                  <a
                    href={`${GITHUB_BASE}/${selectedNode.sourcePath}`}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-[11px] font-mono text-[#111111] hover:underline inline-flex items-center gap-1"
                  >
                    Inspect Source <ExternalLink className="w-3 h-3" />
                  </a>
                </div>
                <h3 className="text-base font-semibold text-[#111111]">{selectedNode.label}</h3>
                <p className="text-xs text-stone-600 leading-relaxed">{selectedNode.summary}</p>
                <ul className="space-y-2 pt-1">
                  {selectedNode.details.map((item) => (
                    <li key={item} className="flex items-start gap-2 text-xs text-stone-700 leading-relaxed">
                      <CheckCircle2 className="w-3.5 h-3.5 text-emerald-700 shrink-0 mt-0.5" />
                      <span>{item}</span>
                    </li>
                  ))}
                </ul>
              </div>

              <div className="p-3 rounded-lg bg-amber-50/70 border border-amber-200 text-[11.5px] text-amber-950 leading-relaxed">
                <strong className="font-mono text-[10.5px] uppercase tracking-wider block mb-0.5">
                  Degradation &amp; Failure Behavior
                </strong>
                {selectedNode.failureMode}
              </div>
            </Panel>
          </div>
        </section>

        {/* 3. Three Core Capability Pillars */}
        <section className="grid grid-cols-1 md:grid-cols-3 gap-5">
          <Panel className="p-5 space-y-3">
            <div className="w-8 h-8 rounded-lg bg-[#FAFAF8] border border-[#E5E5E0] flex items-center justify-center">
              <Code2 className="w-4 h-4 text-[#111111]" />
            </div>
            <h3 className="text-sm font-semibold text-[#111111]">Code Intelligence &amp; Blast Radius</h3>
            <p className="text-xs text-stone-600 leading-relaxed">
              Parses Java 21 with <strong>JavaParser</strong> and TypeScript/JS via lexical stripping. Builds
              disambiguated symbol/dependency graphs, detects circular dependencies via DFS, calculates BFS transitive
              impact chains, and checks layer architecture drift.
            </p>
            <div className="text-[11px] font-mono text-stone-500 pt-2 border-t border-[#EFEFEA]">
              Engines: JavaAstAnalyzer · TypeScriptJsAnalyzer · ArchitectureDriftAnalyzer
            </div>
          </Panel>

          <Panel className="p-5 space-y-3">
            <div className="w-8 h-8 rounded-lg bg-[#FAFAF8] border border-[#E5E5E0] flex items-center justify-center">
              <Radar className="w-4 h-4 text-[#111111]" />
            </div>
            <h3 className="text-sm font-semibold text-[#111111]">Explainable Predictive Baselines</h3>
            <p className="text-xs text-stone-600 leading-relaxed">
              Scores <strong>Bug Risk</strong>, <strong>Test Failure Risk</strong> (distinct from actual CI runs),{' '}
              <strong>Incident Risk</strong>, <strong>Deploy/Rollback Risk</strong>, and{' '}
              <strong>Static Perf Warnings</strong> across 7 deduplicated channels. Uses deterministic rules (
              <code className="font-mono">DETERMINISTIC_BASELINE_V1</code>) — <strong>not trained ML</strong>.
            </p>
            <div className="text-[11px] font-mono text-stone-500 pt-2 border-t border-[#EFEFEA]">
              Anti-leakage chronological held-out evaluation · Uncalibrated probabilities omitted
            </div>
          </Panel>

          <Panel className="p-5 space-y-3">
            <div className="w-8 h-8 rounded-lg bg-[#FAFAF8] border border-[#E5E5E0] flex items-center justify-center">
              <ShieldCheck className="w-4 h-4 text-[#111111]" />
            </div>
            <h3 className="text-sm font-semibold text-[#111111]">Security, Tenancy &amp; Reliability</h3>
            <p className="text-xs text-stone-600 leading-relaxed">
              AES-256-GCM secrets with tenant AAD &amp; key rotation, Shannon entropy secret scanning, CycloneDX 1.5
              SBOM, OASIS SARIF 2.1.0, fail-fast production validator, atomic multi-replica Outbox leases, Kafka DLT
              handling, and Prometheus/Grafana observability.
            </p>
            <div className="text-[11px] font-mono text-stone-500 pt-2 border-t border-[#EFEFEA]">
              Zero localhost prod fallbacks · Single-use fail-closed SSE tickets
            </div>
          </Panel>
        </section>

        {/* 4. End-to-End PR Analysis Demonstration + Processing Timeline */}
        <section id="demo" className="space-y-4">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div>
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-[11px] font-mono uppercase tracking-[0.12em] text-stone-500">
                  End-to-End PR Analysis Demonstration
                </span>
                <Badge className="bg-amber-50 text-amber-900 border-amber-300">
                  SIMULATED SAMPLE WALKTHROUGH
                </Badge>
              </div>
              <h2 className="text-xl font-semibold tracking-[-0.025em] text-[#111111] mt-1">
                8-Stage Pipeline Execution &amp; Policy-as-Code Verdict
              </h2>
            </div>
            <div className="flex items-center gap-2">
              <Button size="sm" onClick={startSimulatedReplay} disabled={simPlaying}>
                <Play className="w-3.5 h-3.5" />
                {simPlaying ? 'Replaying Simulated Stages...' : 'Replay Simulated PR Pipeline'}
              </Button>
              <Button
                size="sm"
                variant="secondary"
                onClick={() => {
                  setSimPlaying(false);
                  setSimStep(SIMULATED_PR_STAGES.length - 1);
                }}
              >
                <RotateCcw className="w-3.5 h-3.5" />
                Show Final State
              </Button>
            </div>
          </div>

          <div className="rounded-lg bg-amber-50/75 border border-amber-200 px-4 py-2.5 text-xs text-amber-950 flex items-start gap-2">
            <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5 text-amber-700" />
            <span>
              <strong>Explicit Simulation Notice:</strong> The walkthrough below is a{' '}
              <strong>simulated illustrative sample</strong> built to demonstrate FORGE&apos;s exact 8 backend stages
              without exposing any private tenant or repository data. To observe real server-emitted SSE stages over
              HTTP, sign in and run a live PR review inside the workspace.
            </span>
          </div>

          {/* When authenticated, ALSO mount real live backend SSE timeline */}
          {token && (
            <div className="space-y-2">
              <div className="text-xs font-mono text-emerald-800 flex items-center gap-2">
                <span className="w-2 h-2 rounded-full bg-emerald-600" />
                Authenticated session detected — real backend SSE events from your workspace appear below automatically:
              </div>
              <LiveProcessingPanel eventTypes={['PR_REVIEW', 'PR_PREDICTION']} autoRecover={false} />
            </div>
          )}

          <div className="grid grid-cols-1 lg:grid-cols-12 gap-5">
            {/* Left: Simulated Sample Diff + Policy Verdict */}
            <div className="lg:col-span-6 space-y-4">
              <Panel className="overflow-hidden border-[#23262F]">
                <div className="px-4 py-2.5 bg-[#111319] border-b border-[#23262F] flex items-center justify-between text-xs font-mono text-zinc-300">
                  <span className="flex items-center gap-2">
                    <GitCompare className="w-3.5 h-3.5 text-indigo-300" />
                    Simulated Input Diff (PR #418)
                  </span>
                  <span className="text-[10px] px-2 py-0.5 rounded bg-amber-500/15 border border-amber-400/35 text-amber-200">
                    SIMULATED SAMPLE
                  </span>
                </div>
                <pre className="p-4 bg-[#0D0E12] text-[11px] font-mono text-zinc-300 overflow-x-auto leading-relaxed">
                  {SIMULATED_DIFF_SAMPLE}
                </pre>
              </Panel>

              <Panel className="p-5 space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-mono uppercase tracking-[0.08em] text-stone-500">
                    Simulated Output Verdict (Deterministic Policy-as-Code)
                  </span>
                  <Badge className="bg-red-50 text-red-700 border-red-200 font-semibold">BLOCK · 84/100</Badge>
                </div>
                <div className="grid grid-cols-2 gap-2.5 text-xs">
                  <div className="p-3 rounded-lg bg-[#FAFAF8] border border-[#EAEAE4]">
                    <div className="font-mono text-[10px] text-stone-500 uppercase">Blocking Rules Triggered</div>
                    <div className="font-medium text-[#111111] mt-1">
                      Critical Security (SQLi) + Destructive DDL (DROP COLUMN)
                    </div>
                  </div>
                  <div className="p-3 rounded-lg bg-[#FAFAF8] border border-[#EAEAE4]">
                    <div className="font-mono text-[10px] text-stone-500 uppercase">Selected Impacted Tests</div>
                    <div className="font-medium text-[#111111] mt-1">
                      3 tests recommended · <span className="font-mono text-amber-700">NOT_EXECUTED</span>
                    </div>
                  </div>
                </div>
                <p className="text-[11.5px] text-stone-600 leading-relaxed">
                  Advisory predictive baseline additionally flags a static N+1 database query warning in{' '}
                  <code className="font-mono">SettlementService.java</code> and elevated rollback risk due to
                  non-reversible schema drop.
                </p>
              </Panel>
            </div>

            {/* Right: Dark-First 8-Stage Timeline Matching LiveProcessingPanel */}
            <div className="lg:col-span-6 rounded-xl bg-[#0D0E12] border border-[#23262F] text-zinc-100 overflow-hidden">
              <div className="px-4 py-3 border-b border-[#1E212A] bg-[#111319] flex items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <Terminal className="w-4 h-4 text-indigo-300" />
                  <span className="text-xs font-semibold">PR Review Pipeline (8 Backend Stages)</span>
                </div>
                <span className="font-mono text-[10px] px-2 py-0.5 rounded-full bg-indigo-500/15 border border-indigo-400/35 text-indigo-200">
                  Stage {simStep + 1} of {SIMULATED_PR_STAGES.length} · SIMULATED
                </span>
              </div>
              <ol className="p-4 space-y-2.5">
                {SIMULATED_PR_STAGES.map((st, idx) => {
                  const state = idx < simStep ? 'done' : idx === simStep ? (simPlaying ? 'active' : 'done') : 'pending';
                  return (
                    <li
                      key={st.stage}
                      className={cn(
                        'rounded-lg px-3 py-2 border transition-colors',
                        state === 'active'
                          ? 'bg-indigo-500/10 border-indigo-400/40'
                          : state === 'done'
                            ? 'bg-[#12151E] border-[#222634]'
                            : 'bg-transparent border-transparent opacity-45'
                      )}
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className="text-xs font-medium text-zinc-100 flex items-center gap-2">
                          <span
                            className={cn(
                              'w-2 h-2 rounded-full',
                              state === 'active'
                                ? 'bg-indigo-400 animate-pulse motion-reduce:animate-none'
                                : state === 'done'
                                  ? 'bg-emerald-400'
                                  : 'bg-zinc-600'
                            )}
                          />
                          {st.label}
                        </span>
                        <span className="font-mono text-[10px] text-zinc-500">{st.stage}</span>
                      </div>
                      {idx <= simStep && (
                        <>
                          <p className="text-[11px] text-zinc-400 mt-1">{st.message}</p>
                          <div className="flex flex-wrap gap-1.5 mt-1.5">
                            {Object.entries(st.details).map(([k, v]) => (
                              <span
                                key={k}
                                className="px-1.5 py-0.5 rounded bg-[#181C28] border border-[#282E40] font-mono text-[10px] text-zinc-300"
                              >
                                <span className="text-zinc-500">{k}:</span> {String(v)}
                              </span>
                            ))}
                          </div>
                        </>
                      )}
                    </li>
                  );
                })}
              </ol>
            </div>
          </div>
        </section>

        {/* 5. Actual Technical Stack */}
        <section className="space-y-4">
          <div>
            <div className="text-[11px] font-mono uppercase tracking-[0.12em] text-stone-500">
              Verified Production Stack (From pom.xml, package.json &amp; docker-compose.yml)
            </div>
            <h2 className="text-xl font-semibold tracking-[-0.025em] text-[#111111] mt-0.5">
              Actual Technology Stack
            </h2>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            {[
              {
                icon: Server,
                title: 'Backend & Engines',
                items: ['Java 21 LTS · Spring Boot 3.5.16', 'JavaParser AST + TS/JS Analyzer', 'Spring Security + JJWT + BCrypt', 'Resilience4j + Spring AI (Optional)'],
              },
              {
                icon: Database,
                title: 'Data & Messaging',
                items: ['PostgreSQL 16 (pgvector) + Flyway V1–V6', 'H2 PG-mode (zero-Docker local dev)', 'Apache Kafka 3.9 + @RetryableTopic DLT', 'Redis 7.4 (Cache, Lua Rate Limit, SSE Tickets)'],
              },
              {
                icon: Layers,
                title: 'Frontend UI',
                items: ['React 18 + TypeScript (strict) + Vite 6', 'TanStack Query v5 + Zustand', 'Monaco Diff Editor + React Flow + Recharts', 'Radix UI + Tailwind CSS + EventSource SSE'],
              },
              {
                icon: Activity,
                title: 'Quality & DevOps',
                items: ['JUnit 5 + MockMvc + ArchUnit', 'PostgreSQL 16 Testcontainers (Linux CI)', 'Vitest + Testing Library + npm audit', 'Multi-stage Docker + GitHub Actions CI + Prometheus'],
              },
            ].map((group) => {
              const Icon = group.icon;
              return (
                <Panel key={group.title} className="p-4 space-y-2.5">
                  <div className="flex items-center gap-2 text-xs font-semibold text-[#111111]">
                    <Icon className="w-4 h-4 text-stone-600" />
                    {group.title}
                  </div>
                  <ul className="space-y-1.5 text-xs text-stone-600">
                    {group.items.map((item) => (
                      <li key={item} className="flex items-start gap-1.5">
                        <span className="text-stone-400">·</span>
                        <span>{item}</span>
                      </li>
                    ))}
                  </ul>
                </Panel>
              );
            })}
          </div>
        </section>

        {/* 6. Engineering Decisions & Trade-Offs */}
        <section id="tradeoffs" className="space-y-4">
          <div>
            <div className="text-[11px] font-mono uppercase tracking-[0.12em] text-stone-500">
              Architectural Trade-Off Analysis
            </div>
            <h2 className="text-xl font-semibold tracking-[-0.025em] text-[#111111] mt-0.5">
              Key Engineering Decisions &amp; Deliberate Trade-Offs
            </h2>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {TRADE_OFFS.map((t) => (
              <Panel key={t.decision} className="p-5 space-y-2.5">
                <div className="flex items-start justify-between gap-2">
                  <h3 className="text-sm font-semibold text-[#111111] flex items-center gap-2">
                    <Scale className="w-4 h-4 text-stone-500 shrink-0" />
                    {t.decision}
                  </h3>
                  <a
                    href={`${GITHUB_BASE}/${t.sourcePath}`}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-[11px] font-mono text-stone-500 hover:text-[#111111] shrink-0"
                    title={`Open ${t.sourcePath}`}
                  >
                    <ExternalLink className="w-3.5 h-3.5" />
                  </a>
                </div>
                <p className="text-xs text-stone-700 leading-relaxed">
                  <strong>Why:</strong> {t.rationale}
                </p>
                <p className="text-xs text-stone-500 leading-relaxed">
                  <strong>Trade-off:</strong> {t.tradeOff}
                </p>
              </Panel>
            ))}
          </div>
        </section>

        {/* 7. Testing & Quality Evidence (Zero Fabricated Metrics) */}
        <section id="evidence" className="space-y-4">
          <div>
            <div className="text-[11px] font-mono uppercase tracking-[0.12em] text-stone-500">
              Honest Verification Ledger (Zero Fabricated Numbers)
            </div>
            <h2 className="text-xl font-semibold tracking-[-0.025em] text-[#111111] mt-0.5">
              Testing, Quality Evidence &amp; Unverified Boundaries
            </h2>
          </div>

          <Panel className="overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="border-b border-[#EFEFEA] bg-[#FAFAF8] text-[10.5px] font-mono uppercase text-stone-500">
                  <tr>
                    <th className="px-5 py-3">Verification Dimension</th>
                    <th className="px-5 py-3">Status</th>
                    <th className="px-5 py-3">Evidence / Exact Scope</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#EFEFEA]">
                  <tr>
                    <td className="px-5 py-3 font-medium text-[#111111]">Backend JUnit 5, Security, Multi-Tenant, SSE &amp; ArchUnit</td>
                    <td className="px-5 py-3"><Badge className="bg-emerald-50 text-emerald-800 border-emerald-200">AUTOMATED IN CI</Badge></td>
                    <td className="px-5 py-3 text-stone-600">Executed via <code className="font-mono">./mvnw test</code> on Java 21 (H2 PG-mode + MockMvc + ArchUnit boundaries).</td>
                  </tr>
                  <tr>
                    <td className="px-5 py-3 font-medium text-[#111111]">PostgreSQL 16 Flyway V1–V6 Migrations (Testcontainers)</td>
                    <td className="px-5 py-3"><Badge className="bg-emerald-50 text-emerald-800 border-emerald-200">AUTOMATED IN LINUX CI</Badge></td>
                    <td className="px-5 py-3 text-stone-600"><code className="font-mono">PostgresMigrationContainersIT</code> runs against real PostgreSQL 16 container in GitHub Actions.</td>
                  </tr>
                  <tr>
                    <td className="px-5 py-3 font-medium text-[#111111]">Frontend TypeScript, Vitest, Production Build &amp; Prod npm Audit</td>
                    <td className="px-5 py-3"><Badge className="bg-emerald-50 text-emerald-800 border-emerald-200">AUTOMATED IN CI</Badge></td>
                    <td className="px-5 py-3 text-stone-600"><code className="font-mono">npm audit --omit=dev --audit-level=high</code>, <code className="font-mono">tsc --noEmit</code>, Vitest, and Vite production bundle.</td>
                  </tr>
                  <tr>
                    <td className="px-5 py-3 font-medium text-[#111111]">Docker Production Images &amp; Compose Config</td>
                    <td className="px-5 py-3"><Badge className="bg-emerald-50 text-emerald-800 border-emerald-200">AUTOMATED IN LINUX CI</Badge></td>
                    <td className="px-5 py-3 text-stone-600">CI validates <code className="font-mono">docker compose config -q</code> and builds frontend, backend, and unified single-container images.</td>
                  </tr>
                  <tr>
                    <td className="px-5 py-3 font-medium text-[#111111]">Code Coverage Percentage (JaCoCo / Istanbul)</td>
                    <td className="px-5 py-3"><Badge className="bg-stone-100 text-stone-700 border-stone-200">Not verified</Badge></td>
                    <td className="px-5 py-3 text-stone-600">No coverage percentage gate is configured; FORGE intentionally avoids citing unmeasured coverage percentages.</td>
                  </tr>
                  <tr>
                    <td className="px-5 py-3 font-medium text-[#111111]">Production Load / Latency Benchmarks &amp; Live User Traffic</td>
                    <td className="px-5 py-3"><Badge className="bg-stone-100 text-stone-700 border-stone-200">Not verified</Badge></td>
                    <td className="px-5 py-3 text-stone-600">No multi-node production load test or live APM traffic telemetry has been executed.</td>
                  </tr>
                  <tr>
                    <td className="px-5 py-3 font-medium text-[#111111]">Trained Supervised ML Model Accuracy</td>
                    <td className="px-5 py-3"><Badge className="bg-stone-100 text-stone-700 border-stone-200">Not verified</Badge></td>
                    <td className="px-5 py-3 text-stone-600">No ML model is trained; predictions run deterministic baselines with chronological held-out evaluation once real outcomes exist.</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </Panel>
        </section>

        {/* 8. Direct GitHub Source Code Map */}
        <section className="space-y-4">
          <div>
            <div className="text-[11px] font-mono uppercase tracking-[0.12em] text-stone-500">
              Source Code Inspection Map
            </div>
            <h2 className="text-xl font-semibold tracking-[-0.025em] text-[#111111] mt-0.5">
              Direct Links to Core GitHub Implementation Files
            </h2>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
            {SOURCE_LINKS.map((item) => (
              <a
                key={item.path}
                href={`${GITHUB_BASE}/${item.path}`}
                target="_blank"
                rel="noopener noreferrer"
                className="p-3.5 rounded-xl bg-white border border-[#E5E5E0] hover:border-[#111111] transition-colors flex items-start justify-between gap-3 group"
              >
                <div className="min-w-0">
                  <div className="text-xs font-semibold text-[#111111] flex items-center gap-1.5">
                    <FileCode2 className="w-3.5 h-3.5 text-stone-500 shrink-0" />
                    <span className="truncate">{item.label}</span>
                  </div>
                  <div className="font-mono text-[10.5px] text-stone-500 truncate mt-1">{item.path}</div>
                </div>
                <ExternalLink className="w-3.5 h-3.5 text-stone-400 group-hover:text-[#111111] shrink-0 mt-0.5" />
              </a>
            ))}
          </div>
        </section>

        {/* 9. Technical Interview Walkthrough */}
        <section id="walkthrough" className="space-y-4">
          <div>
            <div className="text-[11px] font-mono uppercase tracking-[0.12em] text-stone-500">
              Engineering Interview Deep-Dive
            </div>
            <h2 className="text-xl font-semibold tracking-[-0.025em] text-[#111111] mt-0.5">
              Technical Interview Walkthrough
            </h2>
          </div>
          <div className="space-y-2.5">
            {INTERVIEW_QA.map((item, idx) => {
              const isOpen = openQaIndex === idx;
              return (
                <Panel key={item.q} className="overflow-hidden">
                  <button
                    type="button"
                    aria-expanded={isOpen}
                    onClick={() => setOpenQaIndex(isOpen ? null : idx)}
                    className="w-full px-5 py-3.5 text-left flex items-center justify-between gap-4 hover:bg-[#FAFAF8] cursor-pointer"
                  >
                    <span className="text-xs sm:text-[13px] font-semibold text-[#111111] flex items-center gap-2">
                      <Sparkles className="w-3.5 h-3.5 text-stone-500 shrink-0" />
                      {item.q}
                    </span>
                    {isOpen ? (
                      <ChevronUp className="w-4 h-4 text-stone-500 shrink-0" />
                    ) : (
                      <ChevronDown className="w-4 h-4 text-stone-500 shrink-0" />
                    )}
                  </button>
                  {isOpen && (
                    <div className="px-5 pb-4 pt-1 border-t border-[#EFEFEA] space-y-2">
                      <p className="text-xs text-stone-700 leading-relaxed">{item.a}</p>
                      <a
                        href={`${GITHUB_BASE}/${item.sourcePath}`}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="inline-flex items-center gap-1 text-[11px] font-mono text-[#111111] hover:underline"
                      >
                        Source: {item.sourcePath} <ExternalLink className="w-3 h-3" />
                      </a>
                    </div>
                  )}
                </Panel>
              );
            })}
          </div>
        </section>

        {/* 10. Clear Project Limitations */}
        <section id="limitations" className="space-y-4">
          <div>
            <div className="text-[11px] font-mono uppercase tracking-[0.12em] text-stone-500">
              Known Constraints &amp; Scope Boundaries
            </div>
            <h2 className="text-xl font-semibold tracking-[-0.025em] text-[#111111] mt-0.5">
              Clear Project Limitations
            </h2>
          </div>
          <Panel className="p-5">
            <ul className="grid grid-cols-1 md:grid-cols-2 gap-3 text-xs text-stone-700">
              {[
                'AST symbol/dependency indexing currently supports Java 21 and TypeScript/JavaScript; other languages (Python, Go, Rust) fall back to diff-level heuristics.',
                'Predictive scores are deterministic rule/historical baselines (DETERMINISTIC_BASELINE_V1), not trained ML models; probabilities remain null until calibrated.',
                'Static performance warnings inspect PR diffs only — live APM latency traces and load benchmarks are not connected.',
                'Recommended impacted tests are selected statically and marked NOT_EXECUTED; FORGE never executes untrusted repository code on the API server.',
                'Offline SCA inspects direct dependencies in pom.xml and package.json against a curated CVE catalog rather than resolving full transitive lockfile graphs or live OSV APIs.',
                'Live GitHub PR comment publishing and optional OpenAI review enrichment require operator-supplied credentials; without them, FORGE runs deterministically.',
              ].map((lim) => (
                <li key={lim} className="p-3 rounded-lg bg-[#FAFAF8] border border-[#EAEAE4] flex items-start gap-2 leading-relaxed">
                  <Cpu className="w-3.5 h-3.5 text-stone-500 shrink-0 mt-0.5" />
                  <span>{lim}</span>
                </li>
              ))}
            </ul>
          </Panel>
        </section>

        {/* Footer CTA */}
        <footer className="pt-4 pb-8 border-t border-[#E5E5E0] flex flex-wrap items-center justify-between gap-4 text-xs text-stone-500">
          <div className="flex items-center gap-2">
            <Lock className="w-3.5 h-3.5 text-stone-500" />
            <span>FORGE Engineering Showcase · Zero private tenant data exposed on this route</span>
          </div>
          <div className="flex items-center gap-4 font-mono">
            <Link to="/login" className="text-[#111111] hover:underline">Sign In / Register</Link>
            <a
              href="https://github.com/nischay237/forge-ai-platform"
              target="_blank"
              rel="noopener noreferrer"
              className="text-[#111111] hover:underline inline-flex items-center gap-1"
            >
              github.com/nischay237/forge-ai-platform <ExternalLink className="w-3 h-3" />
            </a>
          </div>
        </footer>
      </main>
    </div>
  );
}
