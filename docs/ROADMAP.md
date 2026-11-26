# FORGE Engineering Roadmap & Capability Status

## Delivered Capabilities (Verified on `upgrade/forge-enterprise-v2`)

| Phase | Scope | Verification Status |
|---|---|---|
| Phase 1 | Forensic codebase audit & P0–P3 remediation plan | Completed |
| Phase 2 | Profile isolation, `ProductionSecurityValidator`, AES-256-GCM + AAD + key rotation, JWT/refresh rotation + JTI revocation + lockout, single-use SSE tickets, per-tenant webhook HMAC & rotation, lease Outbox + two-phase JDBC idempotency, Kafka `ORIGINAL_TOPIC` DLT, Resilience4j `LlmReviewClient`, PostgreSQL 16 RLS script | Implemented and tested (`H2 PG-mode` + `MockMvc`; RLS script provided) |
| Phase 3–5 | Upgraded `UnifiedDiffParser`, Java 21 `JavaParser` AST, `TypeScriptJsAnalyzer`, disambiguated graph & DFS cycle detection, `ArchitectureDriftAnalyzer`, `ApiCompatibilityAnalyzer`, `MigrationCompatibilityAnalyzer`, static `TestSelectionService` (`NOT_EXECUTED`), deterministic `ChangeRiskEngine` (`PASS`/`WARN`/`BLOCK`), Policy-as-Code (`GET/PUT /api/v1/policies`) | Implemented and tested |
| Phase 6–8 | Multi-match secret masking + PEM redaction + Shannon entropy (`SECRET-ENTROPY-007`), IaC rules, Maven/npm offline SCA, CycloneDX 1.5 SBOM, OASIS SARIF 2.1.0, persistent `finding_suppressions`, `PromptRegistry`, 12-case `ReviewEvaluationService`, `PostgresMigrationContainersIT` | Implemented and tested (Postgres Testcontainers verified in Linux CI with Docker) |
| Phase 9 | Frontend blank default login, boot `/auth/me` validation, single-flight refresh retry, query cache & pipeline teardown on tenant switch, single-use SSE tickets, `React.lazy` code splitting, Change Risk & Recommended Tests UI, Architecture Drift tab, SARIF/SBOM downloads, Suppressions modal, Policy-as-Code editor, honest health status, Vitest suite | Implemented and tested |
| Phase 10 | Hardened GitHub Actions CI (security hygiene gate + Postgres Testcontainers + frontend strict checks), production-safe `render.yaml`, reconciled docs & runbook | Implemented and tested |
| Predictive Engine M1–M10 | Historical engineering data store (`V6` migration, 9 tenant-isolated tables + RLS), idempotent API & GitHub `workflow_run`/`deployment_status` webhook ingestion, `PrFeatureExtractor`, deterministic `BugRiskPredictor`, `TestFailurePredictor` (separated from actual CI results), `OperationalRiskPredictor` (separate Incident vs Deployment/Rollback), static `PerformanceRegressionAnalyzer`, 7-channel deduplicated `EngineeringRiskAggregator`, non-blocking async Outbox pipeline (`forge.pr-prediction.requested`), chronological held-out `PredictionEvaluationService`, `/api/v1/predictions/*` APIs, `/predictions` dashboard & `ReviewDetailPage` forecast card, unit/integration/RBAC/tenant-isolation/Vitest tests | Implemented and tested (Postgres V6 Testcontainers verified in Linux CI with Docker) |

## Planned / Future Enhancements (Not Yet Implemented)

| Area | Planned Scope | Status |
|---|---|---|
| Calibrated Supervised ML Models | Logistic Regression / Random Forest / Gradient Boosting trained ONLY once sufficient verified, labelled historical outcomes accumulate and beat the deterministic baseline on chronological held-out splits | Planned |
| Live APM / Benchmark Telemetry | Ingestion connectors for OpenTelemetry/Prometheus latency & benchmark profiles to upgrade static performance warnings into measured regression evidence | Planned |
| Identity & Recovery | Email-verified password reset flow and TOTP MFA for `OWNER` accounts | Planned |
| Transitive SCA & Live Feeds | Lockfile resolution (`package-lock.json`, Maven effective POM) + scheduled OSV.dev / NVD advisory sync | Planned |
| Full Semantic TypeScript/Python AST | Dedicated `tsc` / Tree-sitter worker sidecar for full cross-file TS/JS and Python/Go type resolution | Planned |
| Live CI Test Execution Integration | GitHub Check Runs / Actions webhook ingestion to transition selected `NOT_EXECUTED` tests to verified CI pass/fail status | Planned |
| Cloud KMS Integration | Direct AWS KMS / GCP KMS envelope key provider alongside environment keyring | Planned |
