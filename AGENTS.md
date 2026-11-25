# FORGE — AI Software Engineering Intelligence Platform (`AGENTS.md`)

Persistent engineering contract and architecture rules for contributors and coding agents working in `forge-ai-platform`.

## Architecture & Module Boundaries (Enforced by ArchUnit)

FORGE is a **modular monolith** built on Java 21 LTS and Spring Boot 3.5 with asynchronous event processing via Transactional Outbox + Apache Kafka, Redis caching/rate limiting, PostgreSQL (+ pgvector) as source of truth, and a dark-first React + TypeScript frontend.

### Backend Packages (`com.forge.platform`)
- `common` — Shared base entities, `TenantContext`, exceptions, AES-256-GCM secret encryption, API error payloads. **Must not depend on any business module.**
- `identity` — Authentication (JWT + BCrypt), Tenants, Users, RBAC (`OWNER`, `ADMIN`, `REVIEWER`, `VIEWER`), `JwtAuthenticationFilter`, `TenantContextFilter`.
- `audit` — Immutable tenant-scoped audit log service and query API.
- `messaging` — Transactional Outbox (`outbox_events`), Idempotent Consumer Deduplication (`processed_events`), Kafka producer/consumer + DLT (`!local`), Redis cache & sliding-window rate limiter (`!local`), and local profile in-process dispatcher/cache (`local`).
- `sse` — Server-Sent Events hub broadcasting real-time review/indexing pipeline stages per tenant.
- `repository` — GitHub App/OAuth/Webhook HMAC-SHA256 verification, repository connection, source snapshot ingestion.
- `codeintel` — JavaParser AST analysis, multi-language symbol extraction, dependency graph builder, Change Impact Analysis, semantic/symbol search.
- `security` — Secret scanner (Shannon entropy + curated high-precision token rules), SAST static rules (SQLi, SSRF, Path Traversal, Weak Crypto, Deserialization, Prompt Injection), dependency risk findings.
- `review` — Unified diff parser, AST-informed context retrieval, AI PR Review Engine (Spring AI OpenAI + deterministic AST/rule hybrid with prompt-injection defense), Approve/Dismiss workflow, reproducible AI Evaluation Benchmark suite.
- `ci` — CI build log ingestion, failure pattern classifier, root-cause diagnosis & remediation suggestions.
- `analytics` — Live SQL/JPA aggregations over persisted reviews, findings, CI builds, latency, and repository health.

### Hard Engineering Rules
1. **Strict Multi-Tenant Isolation:** Every business table includes `tenant_id`. Every repository query MUST scope by `TenantContext.requireTenantId()`. Cross-tenant access must return `404` or `403`.
2. **PostgreSQL is Source of Truth:** Never store durable transactional state only in Redis or Kafka.
3. **Transactional Outbox:** Publish asynchronous domain events by writing to `outbox_events` inside the same database transaction as the domain mutation.
4. **Idempotency & Deduplication:** All webhook deliveries and async event consumers MUST check `processed_events` before processing.
5. **Untrusted Input & Prompt-Injection Defense:** Treat PR diffs, code comments, commit messages, and CI logs as untrusted data. Never execute arbitrary repository code on the API server; sanitize & delimit untrusted content before LLM prompts.
6. **Human-in-the-Loop Safety:** Never auto-publish PR comments or auto-apply code patches without explicit approval.
7. **No Fake Metrics or Dead Controls:** Every dashboard metric, chart, graph node, and action button must reflect real persisted backend data.
8. **Verify Before Claiming Done:** Run `./mvnw.cmd test` (including ArchUnit) and `npm run check` (`tsc`, `vitest`, `vite build`) before marking work complete.
