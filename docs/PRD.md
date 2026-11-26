# Product Requirements Document — FORGE

## 1. Product Vision
**"Understand the impact of every code change. Predict what could fail before it reaches production."**

**FORGE** is a multi-tenant Predictive Software Engineering & Change Impact Intelligence Platform. Engineering teams connect repositories and historical engineering outcomes so FORGE can parse source ASTs, map dependency graphs and architecture drift, evaluate API and database migration compatibility, select relevant verification tests, detect secrets/SAST/IaC/SCA issues, compute deterministic Policy-as-Code merge recommendations (`PASS` / `WARN` / `BLOCK`), and forecast bug, test failure, production incident, deployment/rollback, and static performance risks with transparent evidence and confidence levels.

## 2. Target Personas & Core Journeys
1. **Staff / Senior Engineer (Reviewer)**
   - Inspects a PR in the Review Workbench: watches live ticket-authenticated SSE progress, reviews the explainable **Change Risk Decision (`PASS` / `WARN` / `BLOCK`)** with triggered policy rules and risk factors, verifies impacted modules & recommended `NOT_EXECUTED` tests, inspects Monaco side-by-side diffs, and explicitly Approves/Dismisses findings before publishing to GitHub.
2. **Application Security Engineer**
   - Monitors the Security Center across tenant repositories for masked secrets (signatures + Shannon entropy), SAST/IaC findings, and offline SCA dependency advisories; exports **OASIS SARIF 2.1.0** and **CycloneDX 1.5 SBOM** artifacts; manages persistent **Finding Suppressions** with audit trails.
3. **Engineering Manager / Platform Owner**
   - Configures workspace **Change Risk Policy-as-Code** thresholds and layer boundaries, rotates per-tenant webhook secrets, reviews immutable RBAC-protected Audit Logs, tracks live SQL analytics, and runs the reproducible **12-case Deterministic Evaluation Benchmark**.

## 3. Functional Requirements
| ID | Module | Requirement | Status |
|---|---|---|---|
| FR-01 | Identity & RBAC | Multi-tenant workspaces, HS256 JWT (`jti`/`iss`/`aud`/`ver`), rotating refresh tokens with reuse family revocation, logout JTI revocation, brute-force lockout (`423`), RBAC (`OWNER`/`ADMIN`/`REVIEWER`/`VIEWER`). | Implemented and tested |
| FR-02 | GitHub Integration | Per-tenant CSPRNG HMAC-SHA256 webhook secrets, secret rotation, replay defense, uniform 401 anti-enumeration, Resilience4j GitHub ingestion. | Implemented and tested |
| FR-03 | Code Intelligence | Java 21 `JavaParser` AST, TS/JS lexical/structural analyzer, disambiguated dependency graph, DFS cycle detection, BFS Change Impact, Architecture Drift API. | Implemented and tested |
| FR-04 | Change Risk & Compatibility | `UnifiedDiffParser`, OpenAPI/Spring `ApiCompatibilityAnalyzer`, Flyway `MigrationCompatibilityAnalyzer`, static `TestSelectionService` (`NOT_EXECUTED`), deterministic `ChangeRiskEngine` (`PASS`/`WARN`/`BLOCK`), Policy-as-Code CRUD. | Implemented and tested |
| FR-05 | Security, SCA, SBOM & SARIF | Multi-match secret masking + PEM redaction + Shannon entropy, SAST + IaC rules, Maven/npm offline SCA, CycloneDX 1.5 SBOM, OASIS SARIF 2.1.0, persistent Finding Suppressions. | Implemented and tested |
| FR-06 | AI Enrichment & Eval | Resilience4j `LlmReviewClient`, `SystemMessage`/`UserMessage` separation, `FORGE-PROMPT-001` quarantine, `PromptRegistry`, 12-case `ReviewEvaluationService`. | Implemented and tested |
| FR-07 | Distributed Reliability | Lease-based Transactional Outbox, two-phase JDBC idempotency, Kafka `@RetryableTopic` + `ORIGINAL_TOPIC` DLT, atomic Lua Redis rate limiter, single-use SSE tickets. | Implemented and tested |
| FR-08 | CI Intelligence & Analytics | CI log failure classifier, live SQL analytics, immutable RBAC-gated Audit Logs, code-split React 18 UI with strict cache isolation. | Implemented and tested |
| FR-09 | Predictive Engineering Intelligence | Flyway `V6` historical outcome store (tests, deployments/rollbacks, confirmed incidents, confirmed defects), idempotent API + GitHub `workflow_run`/`deployment_status` webhook ingestion, feature extraction, deterministic Bug/Test/Incident/Change-Failure predictors, static Performance Regression analyzer, 7-channel deduplicated risk aggregation, chronological held-out evaluation, `/predictions` dashboard & `ReviewDetailPage` integration. | Implemented and tested |

## 4. Non-Functional Requirements
- **Fail-Fast Production Security:** `ProductionSecurityValidator` rejects weak/default secrets or demo seeding under `prod`.
- **Zero Arbitrary Code Execution:** Untrusted repository code is analyzed statically — never compiled or executed on FORGE servers.
- **Honest Safety Claims:** `PASS` never claims guaranteed production safety; statically selected tests are always labeled `NOT_EXECUTED`.
- **Architecture Governance:** Package boundaries and tenant-scoping invariants are enforced automatically by **ArchUnit** in CI.
