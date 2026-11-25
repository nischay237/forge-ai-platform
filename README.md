# FORGE — AI Software Engineering Intelligence Platform

> **Enterprise-grade, multi-tenant engineering intelligence platform built with Java 21 LTS, Spring Boot 3.5, Apache Kafka, Redis, PostgreSQL (+ pgvector), JavaParser AST, Spring AI, Resilience4j, MapStruct, ArchUnit, OpenTelemetry, and a dark-first React + TypeScript + Monaco + React Flow UI.**

---

## Core Engineering Capabilities

- **Multi-Tenant Identity, JWT & RBAC (`identity`)**: Stateless HS256 JWT authentication, BCrypt passwords, `OWNER / ADMIN / REVIEWER / VIEWER` authorization, and strict `tenant_id` isolation across every JPA query.
- **GitHub Integration & Signed Webhooks (`repository`, `review`)**: Repository ingestion (live GitHub API with Resilience4j circuit breaker + retry), constant-time HMAC-SHA256 webhook verification (`X-Hub-Signature-256`), and delivery deduplication (`X-GitHub-Delivery`).
- **JavaParser AST Code Intelligence (`codeintel`)**: Real Java AST parsing extracting classes, methods, cyclomatic complexity, and dependency edges; interactive **React Flow Dependency Graph**; **BFS Change Impact Analysis** computing downstream blast radius & affected tests; and AST-anchored code search.
- **Contextual AI Pull Request Review (`review`)**: Unified diff parser, AST-informed context retrieval, **Spring AI OpenAI** + deterministic AST/concurrency/FinTech/security hybrid engine protected by Resilience4j and XML-fenced **prompt-injection defense**, Monaco side-by-side Diff Workbench, and mandatory **human-in-the-loop approval** before publishing to GitHub.
- **Security Center (`security`)**: Gitleaks-grade secret scanner (AWS, GitHub PAT, Stripe, OpenAI, PEM keys with strict secret masking + AES-256-GCM at-rest encryption) and Semgrep-grade SAST rules (SQLi, SSRF, Path Traversal, Deserialization, Weak Crypto, XSS, Prompt Injection).
- **Distributed Async Reliability (`messaging`, `sse`)**: **Transactional Outbox** (`outbox_events`) written atomically with domain mutations, **Apache Kafka** producer/consumer with `@RetryableTopic` exponential backoff & Dead-Letter Topic (`forge.pr-review.requested.dlt`), **Idempotent Consumer** deduplication (`processed_events`), **Redis** caching & sliding-window rate limiting, and **Server-Sent Events (SSE)** live pipeline progress streaming.
- **CI Failure Intelligence & Analytics (`ci`, `analytics`)**: Automated CI log root-cause classification (test failures, Flyway schema drift, OOM/SIGKILL 137, compilation errors, infra timeouts, security gates), live SQL-backed analytics, immutable **Audit Logs**, and a **reproducible 8-case AI Evaluation Benchmark** measuring Precision, Recall, F1, False-Positive Rate, and Latency.
- **Architecture & Quality Governance**: Automated **ArchUnit** module boundary tests, JUnit 5 + Mockito + Spring Security MockMvc multi-tenant isolation tests, Flyway versioned migrations, Micrometer + Prometheus + OpenTelemetry, Vitest + TypeScript, and GitHub Actions CI.

---

## Quick Start (Local Mode — Zero Docker Needed)

Requires **Java 21 LTS** and **Node.js 20+**.

### 1. Start Spring Boot Backend
```powershell
cd backend
.\mvnw.cmd spring-boot:run
```
- API: `http://localhost:8080/api/v1`
- Swagger / OpenAPI UI: `http://localhost:8080/swagger-ui.html`
- Prometheus Metrics: `http://localhost:8080/actuator/prometheus`
- Pre-seeded Demo Login: `nischay@forge.dev` / `ForgeUser@2026` (`OWNER` of *Acme FinTech Core*)

### 2. Start React + TypeScript Frontend
```powershell
cd frontend
npm install
npm run dev
```
- App: `http://localhost:5173`

---

## Full Production Stack (Docker Compose: PostgreSQL + pgvector + Kafka + Redis + Prometheus + Grafana)

```bash
docker compose up --build
```

---

## Verification & Automated Tests

```powershell
# Backend (JUnit 5 + Multi-Tenant Integration + ArchUnit)
cd backend
.\mvnw.cmd test

# Frontend (TypeScript + Vitest + Production Bundle)
cd frontend
npm run typecheck
npm run test
npm run build
```

## Documentation
- [PRD](docs/PRD.md)
- [System Architecture & Kafka Catalogue](docs/ARCHITECTURE.md)
- [Security Threat Model](docs/SECURITY.md)
- [16-Week Roadmap](docs/ROADMAP.md)
- [Operational Runbook](docs/RUNBOOK.md)
- [ADR-001: Modular Monolith & Outbox](docs/adr/ADR-001-modular-monolith.md)
