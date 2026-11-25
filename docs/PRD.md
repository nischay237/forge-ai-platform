# Product Requirements Document — FORGE

## 1. Product Vision
**FORGE** is an enterprise-grade, multi-tenant **AI Software Engineering Intelligence Platform**. Engineering teams connect GitHub repositories so FORGE can parse source ASTs, map dependency graphs, analyze pull requests with repository context, detect leaked secrets and security vulnerabilities, diagnose failing CI builds, and measure both engineering delivery velocity and AI review accuracy.

## 2. Target Personas & Core Journeys
1. **Staff / Senior Engineer (Reviewer)**
   - Opens a PR in FORGE Review Workbench, watches real-time SSE pipeline progress (`Webhook -> Outbox -> AST -> Scanners -> AI Context -> Persisted`), inspects Monaco side-by-side diffs with inline findings, checks Change Impact blast radius, and explicitly **Approves** or **Dismisses** each finding before anything touches GitHub.
2. **Application Security Engineer**
   - Monitors the Security Center across all tenant repositories for leaked API keys (entropy + pattern evidence), SAST findings (SQLi, SSRF, Path Traversal, Weak Crypto, Prompt Injection attempts), and triages findings (`OPEN -> MITIGATED / FALSE_POSITIVE`) with full audit trails.
3. **Engineering Manager / Tech Lead**
   - Tracks real database-computed Engineering Analytics (mean review duration, CI build pass rate, findings by severity, repository health) and runs the reproducible **AI Review Quality Evaluation Benchmark** (Precision, Recall, F1, False-Positive Rate, Latency per prompt version).

## 3. Functional Requirements
| ID | Module | Requirement |
|----|--------|-------------|
| FR-01 | Identity & RBAC | Multi-tenant workspaces with JWT authentication, BCrypt passwords, and role-based access (`OWNER`, `ADMIN`, `REVIEWER`, `VIEWER`). |
| FR-02 | GitHub Integration | Connect repositories, verify signed GitHub webhooks via HMAC-SHA256 (`X-Hub-Signature-256`), and deduplicate deliveries via `X-GitHub-Delivery`. |
| FR-03 | Code Intelligence | Parse Java source via **JavaParser AST** (plus multi-language structural extraction), construct interactive dependency graphs, compute Change Impact blast radius, and run symbol/semantic search. |
| FR-04 | AI PR Review | Parse unified diffs, retrieve AST-informed context (impacted dependents & related tests), defend against prompt injection, emit structured findings (file, line, severity, confidence, evidence, suggested patch), and support Approve/Dismiss. |
| FR-05 | Security Center | Scan source & diffs for high-entropy secrets (Gitleaks-grade signatures) and SAST vulnerability patterns (Semgrep-grade rules), with masked secret display and status triage. |
| FR-06 | CI Intelligence | Ingest CI build runs & raw logs, classify failure root causes (compilation, test assertion, OOM, migration/dependency conflict, timeout), and generate actionable remediations. |
| FR-07 | Async Reliability | Persist domain mutations + `outbox_events` atomically; dispatch via Kafka (prod) or in-process outbox worker (local), deduplicate via `processed_events`, retry with Resilience4j, and route poison events to Dead-Letter Topic (`forge.reviews.dlt`). |
| FR-08 | Live Progress & Analytics | Stream pipeline stage updates via Server-Sent Events (SSE), compute analytics exclusively from persisted database rows, and maintain immutable Audit Logs. |

## 4. Non-Functional Requirements
- **Strict Tenant Isolation:** Every business entity carries `tenant_id`; cross-tenant resource lookups return `404`/`403` and are enforced by integration tests.
- **Zero Arbitrary Code Execution:** Repository code is parsed statically via AST/regex/scanners; untrusted repo code is NEVER executed on the API server.
- **Observability:** Actuator health checks, Prometheus metrics (`/actuator/prometheus`), OpenTelemetry tracing bridge, and structured JSON/console logging.
- **Architecture Governance:** Package layering and module boundaries enforced automatically via **ArchUnit** in CI.
