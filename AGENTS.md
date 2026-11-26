# FORGE — Engineering Contract & Architecture Rules (`AGENTS.md`)

Persistent engineering contract and architectural invariants for contributors and coding agents working in `forge-ai-platform`.

## Core Positioning
**"Understand the impact of every code change. Predict what could fail before it reaches production."**
Deterministic analyzers, dependency graphs, and Policy-as-Code own `PASS` / `WARN` / `BLOCK` merge decisions. Predictive baselines provide advisory failure risk forecasts grounded in real historical data and must NEVER auto-block on uncertain historical signals or fabricate probabilities/accuracy.

## Architecture & Module Boundaries (Enforced by ArchUnit)

FORGE is a **modular monolith** built on Java 21 LTS and Spring Boot 3.5 with Flyway V1–V6 migrations, Transactional Outbox + Apache Kafka, Redis caching/rate limiting, PostgreSQL 16 as source of truth, and a React 18 + TypeScript frontend.

### Backend Packages (`com.forge.platform`)
- `common` — Base entities, `TenantContext`, exceptions, versioned AES-256-GCM encryption (`SecretCryptoService` with AAD + key rotation), `ProductionSecurityValidator`, API error payloads. **Must not depend on any business module.**
- `identity` — Authentication (HS256 JWT with `jti`/`iss`/`aud`/`ver`, rotating `refresh_tokens` with family reuse revocation, `TokenRevocationService`, brute-force lockout via `AuthLockoutService`), Tenants, Users, RBAC (`OWNER`, `ADMIN`, `REVIEWER`, `VIEWER`), `JwtAuthenticationFilter`.
- `audit` — Immutable tenant-scoped audit log service and RBAC-protected query API.
- `messaging` — Lease-based Transactional Outbox (`outbox_events`), two-phase JDBC Idempotency (`processed_events`), Kafka producer/consumer with `ORIGINAL_TOPIC` DLT routing (`!local`), atomic Lua Redis rate limiter & cache (`!local`), and local in-process dispatcher/cache (`local`).
- `sse` — Single-use SSE ticket issuance (`SseTicketService`), bounded per-tenant `SseHubService` (20 emitters/tenant, 30m TTL, `@PreDestroy` cleanup).
- `repository` — Per-tenant CSPRNG webhook secrets, constant-time HMAC-SHA256 verification, replay protection, Resilience4j GitHub ingestion client, repository lifecycle.
- `codeintel` — Java 21 `JavaParser` AST analyzer, `TypeScriptJsAnalyzer`, disambiguated dependency graph, DFS cycle detection, BFS Change Impact analysis, `ArchitectureDriftAnalyzer`. **Must not depend on `review` or `ci`.**
- `security` — Multi-match secret scanner + Shannon entropy detector, SAST + IaC rules, `DependencyAndSbomService` (Maven/npm offline SCA + CycloneDX 1.5 SBOM), `SarifExportService` (SARIF 2.1.0), persistent `finding_suppressions`.
- `review` — `UnifiedDiffParser`, `ApiCompatibilityAnalyzer`, `MigrationCompatibilityAnalyzer`, `TestSelectionService` (explicitly `NOT_EXECUTED`), deterministic `ChangeRiskEngine`, `PolicyService` / `PolicyController`, Resilience4j `LlmReviewClient`, `AiReviewEngine`, `PromptRegistry`, 12-case `ReviewEvaluationService`.
- `prediction` — `HistoricalDataIngestionService` (idempotent API + GitHub `workflow_run`/`deployment_status` webhooks), `PrFeatureExtractor`, `BugRiskPredictor`, `TestFailurePredictor`, `OperationalRiskPredictor` (separate Incident vs Deployment/Rollback), `PerformanceRegressionAnalyzer` (static perf warnings), `EngineeringRiskAggregator` (7 deduplicated channels), `PredictionPipelineService` (`forge.pr-prediction.requested`), `PredictionEvaluationService` (chronological held-out validation), `PredictionController`. `common`, `audit`, and `codeintel` must NOT depend on `prediction`.
- `ci` — CI build log ingestion, failure pattern classifier, root-cause diagnosis & remediation suggestions.
- `analytics` — Live SQL/JPA aggregations over persisted reviews, findings, CI builds, latency, and repository health.

### Hard Engineering Rules
1. **Strict Multi-Tenant Isolation:** Every business table includes `tenant_id` and composite `(tenant_id, id)` keys. Every query MUST scope by `TenantContext.requireTenantId()`, never a client-supplied tenant header/body field. Background workers must explicitly propagate and clear `TenantContext`.
2. **Fail-Fast Production Security:** Never embed default JWT secrets, encryption keys, webhook secrets, or demo credentials in production configs, Dockerfiles, README, or frontend source. `DemoWorkspaceSeeder` runs ONLY under `@Profile("demo & !prod")` with `forge.seed.enabled=true`.
3. **No Tokens in URLs:** SSE authentication MUST use single-use tickets from `POST /api/v1/stream/ticket`; never accept JWTs in `?token=` query params.
4. **Transactional Outbox & Two-Phase Idempotency:** Write domain mutations + `outbox_events` in the same transaction; claim-publish-finalize leases outside long DB locks; mark `processed_events` `IN_PROGRESS` -> `COMPLETED` and release claim on retryable failure.
5. **Untrusted Input & Prompt-Injection Defense:** Never execute untrusted repository code on the API server. Quarantine `FORGE-PROMPT-001` lines, escape XML boundary tags, and separate `SystemMessage` from `UserMessage`.
6. **Honest Evidence & HITL Safety:** Never claim unexecuted tests have passed; never claim `PASS` guarantees production safety; never auto-publish to GitHub without explicit approval.
7. **Frontend Cache Hygiene:** Purge React Query cache and transient pipeline history on logout or tenant switch.
8. **Verify Before Claiming Done:** Run `.\mvnw.cmd test` (backend + ArchUnit), `npm run typecheck`, `npm run test`, and `npm run build` (frontend) before committing.
