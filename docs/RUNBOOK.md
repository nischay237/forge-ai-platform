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
3. `ProductionSecurityValidator` will fail startup immediately if any production secret is missing, shorter than 32 bytes, matches a known dev placeholder, or if `demo` / `FORGE_SEED_ENABLED=true` is enabled under `prod`.

---

## 3. Enabling PostgreSQL 16 Row-Level Security (RLS)
For defense-in-depth tenant isolation at the PostgreSQL layer, apply [docs/postgres-rls.sql](postgres-rls.sql) as a database superuser/owner against your production database, ensuring the application role is non-superuser and does not have `BYPASSRLS`:

```bash
psql "$SPRING_DATASOURCE_URL" -f docs/postgres-rls.sql
```

---

## 4. Secret Rotation Procedures

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

## 5. Troubleshooting Outbox Leases & Kafka DLT
- Inspect tenant-scoped outbox counts via `GET /api/v1/settings` / actuator metrics or query `outbox_events` filtered by `status IN ('PENDING', 'FAILED')`.
- Stuck leases (`locked_until < now()`) are automatically reclaimed on subsequent outbox polling cycles.
- Messages routed to `*.dlt` are recorded in `audit_logs` with `tenant_id`, original topic header (`kafka_dlt-original-topic`), and failure reason. Inspect the root cause before replaying.

---

## 6. Automated Verification Commands

```powershell
# Backend unit, security, multi-tenant, Change Risk & ArchUnit tests
cd backend
.\mvnw.cmd test

# Real PostgreSQL 16 Flyway V1-V6 Testcontainers test (requires Docker daemon; runs in GitHub Actions CI)
.\mvnw.cmd test -Dtest=PostgresMigrationContainersIT

# Frontend strict typecheck, Vitest suite & code-split production build
cd frontend
npm run typecheck
npm run test
npm run build
```
