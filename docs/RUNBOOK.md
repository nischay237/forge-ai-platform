# FORGE Operational & Testing Runbook

## 1. Local Development (Zero Docker Required)

### Clean Local Boot (Recommended)
Runs against an H2 PostgreSQL-mode database with Flyway V1–V6 migrations and in-process outbox dispatch. No demo credentials are created automatically.

```powershell
# Terminal 1: Backend (Java 21 LTS)
cd backend
.\mvnw.cmd spring-boot:run
```
- API Base: `http://localhost:8080/api/v1`
- Swagger / OpenAPI UI: `http://localhost:8080/swagger-ui.html`
- Prometheus Metrics: `http://localhost:8080/actuator/prometheus`

```powershell
# Terminal 2: Frontend (Node.js 22+)
cd frontend
npm ci
npm run dev
```
- App URL: `http://localhost:5173` — Register a workspace on `/login` using the **Register** tab.

### Isolated Demo Mode (Explicit Opt-In)
`DemoWorkspaceSeeder` only activates when **both** the `demo` profile (without `prod`) and `FORGE_SEED_ENABLED=true` are explicitly enabled:

```powershell
cd backend
$env:SPRING_PROFILES_ACTIVE = "local,demo"
$env:FORGE_SEED_ENABLED = "true"
$env:FORGE_DEMO_PASSWORD = "<strong-local-demo-password>"
.\mvnw.cmd spring-boot:run
```

---

## 2. Production Stack (Docker Compose)

1. Copy `.env.example` to `.env` and populate strong random values for:
   - `POSTGRES_PASSWORD`, `REDIS_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`
   - `FORGE_JWT_SECRET` (at least 32 random bytes, e.g. `openssl rand -base64 48`)
   - `FORGE_ENCRYPTION_KEY` (at least 32 random bytes, e.g. `openssl rand -base64 32`)
   - `FORGE_ENCRYPTION_KEY_ID` (default `k1`)
   - `FORGE_CORS_ORIGINS` (explicit allowed frontend origin(s))
2. Start the stack:
   ```bash
   docker compose --env-file .env up --build
   ```
3. `ProductionSecurityValidator` will fail startup immediately if any production secret is missing, shorter than 32 bytes, matches a known dev placeholder, if `SPRING_DATASOURCE_URL` / `SPRING_DATA_REDIS_HOST` / `SPRING_KAFKA_BOOTSTRAP_SERVERS` is missing or targets `localhost`/`127.0.0.1`/H2, if `SPRING_DATA_REDIS_PASSWORD` or `FORGE_CORS_ORIGINS` is missing/wildcard (`*`), or if `demo` / `FORGE_SEED_ENABLED=true` is enabled under `prod`.

---

## 3. Observability, Health Probes & Correlation Tracing

### Health Probes
- Overall health: `GET /actuator/health` (unauthenticated; detailed component breakdown shown only when authorized)
- Kubernetes / Render liveness probe: `GET /actuator/health/liveness`
- Readiness probe: `GET /actuator/health/readiness` (used by `Dockerfile` and `backend/Dockerfile` `HEALTHCHECK`; unified `Dockerfile` launches the JVM via `exec java` so `SIGTERM` triggers Spring Boot graceful shutdown cleanly)

### Structured Logging & Correlation IDs
- Every HTTP request passes through `CorrelationIdFilter`, which accepts a validated `X-Correlation-Id` header or generates a UUID, echoes `X-Correlation-Id` on the response, and binds `correlationId` + `tenantId` to SLF4J MDC.
- `OutboxService` embeds `correlationId` in outbox event payloads; `KafkaDomainEventBus` / `KafkaDomainEventListener` and `LocalDomainEventBus` propagate `correlationId` and `tenantId` into async worker MDC and SSE `PipelineEvent` telemetry.
- In `prod`, `application-prod.yml` enables Spring Boot native Logstash JSON structured logging (`logging.structured.format.console: logstash`). Secrets, JWTs, webhook signatures, and raw payloads are never logged.

### Prometheus / Micrometer Metrics (`GET /actuator/prometheus`, `OWNER`/`ADMIN`)
| Metric | Type | Purpose |
|---|---|---|
| `forge.reviews.completed` / `forge.reviews.duration` | Counter / Timer | PR review throughput & latency |
| `forge.predictions.completed` / `forge.predictions.failed` / `forge.predictions.duration` | Counter / Timer | Predictive baseline throughput, errors & duration |
| `forge.historical.ingested` | Counter | Real historical outcome records ingested |
| `forge.outbox.published` / `forge.outbox.failed` | Counter | Outbox dispatch outcomes |
| `forge.outbox.pending` / `forge.outbox.dead_letter` | Gauge | Current pending backlog & dead-lettered rows |
| `forge.outbox.oldest_pending_age_seconds` | Gauge | Age of oldest undispatched outbox event (alert if > 60s) |
| `forge.kafka.dlt.events` | Counter | Domain events routed to Kafka `*.dlt` topics |
| `forge.webhooks.processed{event,outcome}` | Counter | GitHub webhook acceptance, duplicates, rejections & size-limit blocks |
| `forge.sse.connections.active` / `forge.sse.events.delivered` / `forge.sse.events.dropped` | Gauge / Counter | Active SSE clients and real-time event delivery health |
| `forge.redis.errors{operation}` | Counter | Redis read/write/rate-limit/ticket-redemption errors |
| `hikaricp_connections_*` | Gauge | PostgreSQL HikariCP active, idle & pending connections |

---

## 4. Enabling PostgreSQL 16 Row-Level Security (RLS)
For defense-in-depth tenant isolation at the PostgreSQL layer, apply [docs/postgres-rls.sql](postgres-rls.sql) as a database superuser/owner against your production database, ensuring the application role is non-superuser and does not have `BYPASSRLS`:

```bash
psql "$SPRING_DATASOURCE_URL" -f docs/postgres-rls.sql
```

---

## 5. Secret Rotation Procedures

### Rotating AES-256-GCM Encryption Key (`FORGE_ENCRYPTION_KEY`)
`SecretCryptoService` stores ciphertexts as `v1:<kid>:<base64(iv||ciphertext)>` with tenant/purpose AAD.
1. Generate a new 32-byte key (`k2`).
2. Set `FORGE_ENCRYPTION_KEY_ID=k2`, `FORGE_ENCRYPTION_KEY=<new-k2-key>`, and add the prior key to `FORGE_ENCRYPTION_PREVIOUS_KEYS=k1:<old-k1-key>`.
3. Restart backend instances — new writes encrypt under `k2` while existing `k1` records decrypt seamlessly.
4. Re-save or run `reEncrypt` across stored tenant credentials, then remove `k1` from `FORGE_ENCRYPTION_PREVIOUS_KEYS`.

### Rotating a Tenant GitHub Webhook Secret
1. As `OWNER` or `ADMIN`, call `POST /api/v1/settings/rotate-webhook-secret` (or click **Rotate Secret** on the Settings page).
2. Copy the newly generated `whsec_...` value (shown once) and update the corresponding GitHub repository webhook secret immediately.

---

## 6. Incident Recovery & Troubleshooting

### Outbox Backlog, Leases & Dead-Letter Recovery
- Monitor `forge.outbox.pending`, `forge.outbox.dead_letter`, and `forge.outbox.oldest_pending_age_seconds` in Prometheus/Grafana.
- Outbox rows use atomic per-row conditional claiming (`tryClaimEvent`), so multiple backend replicas cannot double-claim rows. Crashed worker leases (`locked_until <= now()`) automatically become claimable on the next polling cycle.
- After resolving an underlying Kafka outage, dead-lettered tenant outbox rows (`status = 'DEAD_LETTER'`) can be requeued safely via `OutboxService.requeueDeadLetterEvents(tenantId)` or inspected in `outbox_events` (`last_error`, `retry_count`).

### Kafka Dead-Letter Topics (`*.dlt`)
- Messages routed to `forge.pr-review.requested.dlt`, `forge.pr-prediction.requested.dlt`, or `forge.repo-index.requested.dlt` increment `forge.kafka.dlt.events`, emit a terminal `FAILED` SSE event, and write a tenant-scoped `EVENT_DEAD_LETTERED` record to `audit_logs`.
- Inspect `correlationId` in structured logs and `audit_logs` to identify root cause before replaying messages.

### Redis Outage Behavior
- Standard cache calls (`RedisForgeCacheService.get/put/evict`) degrade safely to cache misses with warning logs and `forge.redis.errors` increments; core APIs continue serving from PostgreSQL.
- Rate limiting (`RedisRateLimiterService`) fails open with `forge.redis.errors{operation="rate_limit"}` to preserve availability during transient Redis interruptions.
- Single-use SSE ticket redemption (`consumeOnce`) **fails closed** during a Redis outage to prevent ticket replay/bypass; once Redis recovers, frontend clients automatically reconnect and hydrate missed stages via `GET /api/v1/stream/operations`.

### Database Pool Exhaustion
- Check `hikaricp_connections_pending` and `hikaricp_connections_active` (prod defaults: max 20, leak detection 30s). Because `OutboxService.pollAndDispatch()` publishes to Kafka outside DB transactions, pool saturation typically indicates slow queries or runaway client traffic — correlate slow requests by `correlationId`.

---

## 7. Automated Verification Commands

```powershell
# Backend unit, security, multi-tenant, SSE, predictive & ArchUnit tests
cd backend
.\mvnw.cmd verify

# Real PostgreSQL 16 Flyway V1-V6 Testcontainers test (requires Docker daemon; runs in GitHub Actions CI)
.\mvnw.cmd test -Dtest=PostgresMigrationContainersIT

# Frontend strict typecheck, production dependency audit, Vitest suite & production build
cd frontend
npm audit --omit=dev --audit-level=high
npm run typecheck
npm run test
npm run build
```
