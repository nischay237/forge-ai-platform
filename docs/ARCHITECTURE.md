# FORGE System Architecture

## 1. High-Level Container Architecture (C4)

```mermaid
flowchart TB
    Dev["Engineer Browser (React + TypeScript + Monaco + React Flow)"]
    GH["GitHub (Webhooks + REST API)"]
    LLM["LLM Provider (Spring AI OpenAI / Hybrid Local Engine)"]

    subgraph FORGE["FORGE Modular Monolith (Java 21 + Spring Boot 3.5)"]
        API["REST API + Spring Security JWT + TenantContextFilter"]
        SSE["SSE Pipeline Stream Hub"]
        MOD["Domain Modules: Identity | Repo | CodeIntel (JavaParser) | Security | Review | CI | Analytics | Audit"]
        OUTBOX["Transactional Outbox Poller + Idempotent Consumer"]
    end

    PG[("PostgreSQL 16 + pgvector (Source of Truth + Flyway)")]
    KAFKA[["Apache Kafka (forge.pr-review.requested + .dlt)"]]
    REDIS[("Redis 7 (Cache + Sliding-Window Rate Limiter)")]
    OBS["Micrometer + Prometheus + Grafana + OpenTelemetry"]

    Dev -->|"HTTPS / REST + JWT"| API
    Dev -->|"Server-Sent Events"| SSE
    GH -->|"Signed HMAC-SHA256 Webhook"| API
    API --> MOD
    MOD -->|"ACID Tx (Domain + Outbox)"| PG
    OUTBOX -->|"Poll & Publish"| KAFKA
    KAFKA -->|"Idempotent Consumer + Retry + DLT"| MOD
    MOD -->|"Cache + Rate Limit"| REDIS
    MOD -->|"Resilience4j CircuitBreaker + Retry"| LLM
    MOD -->|"Broadcast Progress"| SSE
    FORGE -->|"Metrics & Traces"| OBS
```

## 2. Event-Driven Pull Request Review Pipeline

```mermaid
sequenceDiagram
    participant Client as Developer / GitHub Webhook
    participant API as Spring Boot API
    participant DB as PostgreSQL (Source of Truth)
    participant Outbox as Outbox Dispatcher / Kafka
    participant Worker as Review Pipeline Worker
    participant SSE as SSE Stream

    Client->>API: POST /api/v1/reviews (or Signed GitHub Webhook)
    API->>DB: Tx: Insert PR Review (QUEUED) + OutboxEvent + AuditLog
    API-->>Client: 202 Accepted (reviewId, status=QUEUED)
    Outbox->>DB: Poll PENDING outbox_events
    Outbox->>Worker: Publish to Kafka topic forge.pr-review.requested
    Worker->>DB: Check processed_events (Idempotency Guard)
    Worker->>SSE: Stage 1: INGESTING_DIFF
    Worker->>SSE: Stage 2: AST_IMPACT_ANALYSIS (JavaParser)
    Worker->>SSE: Stage 3: SECURITY_SCANNING (Secrets + SAST)
    Worker->>SSE: Stage 4: AI_CONTEXT_REVIEW (Prompt-Injection Guard + LLM/Rules)
    Worker->>DB: Persist ReviewFindings + SecurityFindings + Update Status COMPLETED
    Worker->>SSE: Stage 5: COMPLETED (final finding summary)
```

## 3. Database ER Overview (Flyway Managed)
- `tenants` (1) -> (N) `users`, `repositories`, `pull_request_reviews`, `security_findings`, `ci_builds`, `audit_logs`, `evaluation_runs`
- `repositories` (1) -> (N) `source_files`, `code_symbols`, `code_dependencies`, `pull_request_reviews`, `security_findings`, `ci_builds`
- `pull_request_reviews` (1) -> (N) `review_findings`
- `outbox_events` (transactional event queue with `status`, `retry_count`, `next_attempt_at`, `error_message`)
- `processed_events` (idempotency deduplication table keyed on `event_key`)

## 4. Kafka Event Catalogue
| Topic | Producer | Consumer | Key | Retry / DLT Policy |
|-------|----------|----------|-----|--------------------|
| `forge.pr-review.requested` | `OutboxPublisher` | `PrReviewEventConsumer` | `tenantId:reviewId` | 3 attempts exponential backoff -> `forge.pr-review.dlt` |
| `forge.repo-index.requested` | `OutboxPublisher` | `RepoIndexEventConsumer` | `tenantId:repoId` | 3 attempts exponential backoff -> `forge.repo-index.dlt` |
| `forge.pr-review.dlt` | Kafka DLT Handler | Dead-Letter Audit / Alerting | `tenantId:reviewId` | Persisted to audit log & failed status |

## 5. Module Dependency Rules (Enforced by ArchUnit)
- `common` depends on NO other `com.forge.platform.*` module.
- Domain modules (`repository`, `codeintel`, `security`, `review`, `ci`, `analytics`) may depend on `common`, `identity`, `audit`, `messaging`, `sse`, and designated upstream domain services — never circularly.
- Controllers never access JPA repositories directly without going through a service layer for complex domain writes.
