# FORGE Security Threat Model & Hardening Controls

## 1. Profile Separation & Fail-Fast Secret Validation
- **Threat:** Accidentally deploying FORGE to production with development JWT secrets, default encryption keys, default database passwords, or pre-seeded demo admin credentials.
- **Controls:**
  - Profiles are strictly separated: `application.yml` (shared defaults, `forge.seed.enabled=false`), `application-local.yml`, `application-demo.yml`, `application-test.yml`, and `application-prod.yml`.
  - **`ProductionSecurityValidator`** runs at startup under `prod` (and validates secret length/entropy everywhere) and aborts boot if `FORGE_JWT_SECRET` (< 32 bytes or known dev placeholder), `FORGE_ENCRYPTION_KEY` (missing or dev placeholder), `SPRING_DATASOURCE_PASSWORD` (weak/dev default), or `demo` / `forge.seed.enabled=true` is active in production.
  - **`DemoWorkspaceSeeder`** is gated by `@Profile("demo & !prod")` and `@ConditionalOnProperty(name="forge.seed.enabled", havingValue="true", matchIfMissing=false)`, never logs plaintext passwords, and requires an explicit operator password (`FORGE_DEMO_PASSWORD`).
  - `docker-compose.yml` binds stateful services exclusively to `127.0.0.1` and requires environment-supplied credentials via `.env` (documented in `.env.example`).

## 2. Multi-Tenant Isolation & Defense in Depth
- **Threat:** Cross-tenant IDOR (`GET /api/v1/reviews/{otherTenantId}`), repository cross-linking, cross-tenant outbox/Kafka leakage, or React Query cache bleed after account switch.
- **Controls:**
  - Every domain table persists `tenant_id` with composite `(tenant_id, id)` unique keys (Flyway `V4__security_and_reliability_hardening.sql`) and tenant-scoped JPA queries.
  - `JwtAuthenticationFilter` derives `tenantId` solely from the verified JWT claims into thread-local `TenantContext` (cleared in `finally`); client-supplied tenant IDs are never trusted.
  - Async Kafka/outbox workers explicitly bind `TenantContext.setTenantId(event.getTenantId())` and clear it in `finally`.
  - ArchUnit (`ArchitectureBoundaryTest`) forbids unscoped `SourceFileJpaRepository.findById`.
  - Optional **PostgreSQL 16 Row-Level Security (RLS)** policies are provided in [docs/postgres-rls.sql](postgres-rls.sql) for defense-in-depth at the database layer.
  - On the frontend, `useForgeStore.clearSession()` and tenant switches call `queryClient.clear()` and wipe `pipelineHistory`.

## 3. Authentication, Session Lifecycle & RBAC
- **Threat:** Stolen access/refresh tokens, replay after logout, brute-force password guessing, or privilege escalation by `VIEWER`/`REVIEWER` roles.
- **Controls:**
  - **Access Tokens:** HS256 JWTs (>= 256-bit key) carrying `jti`, `iss`, `aud`, `ver` (`tokenVersion`), `tenantId`, and `role`. `JwtAuthenticationFilter` validates signature, issuer, audience, JTI revocation list (`TokenRevocationService`), user active status, and `tokenVersion` on every request.
  - **Rotating Refresh Tokens:** Opaque CSPRNG tokens stored hashed (SHA-256) in `refresh_tokens` with `family_id`. `POST /api/v1/auth/refresh` rotates the token on each use. Reuse of an already-rotated token triggers **family-wide revocation in `Propagation.REQUIRES_NEW`** (`AuthLockoutService.revokeRefreshFamily`) so revocation commits even when HTTP 401 is returned.
  - **Logout:** `POST /api/v1/auth/logout` revokes the access token `jti` until expiry and revokes the associated refresh token family.
  - **Brute-Force Lockout:** 5 consecutive failed logins lock the account for 15 minutes (`HTTP 423 ACCOUNT_LOCKED`), persisted via `REQUIRES_NEW` transaction (`AuthLockoutService`) alongside immutable audit entries.
  - **RBAC Matrix:**
    - `OWNER` / `ADMIN`: Workspace settings, webhook secret rotation, GitHub PAT management, audit log inspection, Change Risk policy editing (`PUT /api/v1/policies`), finding suppression management, repository connect/delete.
    - `REVIEWER`: Trigger PR reviews, recompute predictions (`POST /api/v1/predictions/{id}/run`), ingest historical outcomes (`POST /api/v1/predictions/history/*`), run held-out evaluations (`POST /api/v1/predictions/evaluations/run`), approve/dismiss findings, publish approved reviews, create finding suppressions, ingest CI builds.
    - `VIEWER`: Read-only access to overview, code intelligence, reviews, predictions (`/api/v1/predictions/overview`, `/{id}`, `/evidence`, `/tests`, `/history`, `/evaluations`), security dashboard, CI diagnostics, and analytics. Mutations return `HTTP 403 Forbidden`.
  - **Read-Only Session Validation:** `GET /api/v1/auth/me` validates the active session without minting fresh credentials.

## 4. SSE Authentication (No Tokens in URLs)
- **Threat:** Access token leakage in browser history, proxy access logs, CDN logs, or `Referer` headers via `?token=<jwt>`.
- **Controls:**
  - Query-parameter JWT authentication (`?token=`) is completely removed.
  - Clients exchange their `Authorization: Bearer` token via `POST /api/v1/stream/ticket` for a **single-use, 30-second opaque ticket** (`SseTicketService`).
  - `GET /api/v1/stream/events?ticket=...` consumes the ticket atomically via `ForgeCacheService.consumeOnce()` (preventing replay), binds the emitter to the ticket's `tenantId`, and caps concurrent emitters at **20 per tenant** with 30-minute timeouts and `@PreDestroy` graceful shutdown.

## 5. GitHub Webhook Security
- **Threat:** Forged webhooks, cross-tenant secret reuse, tenant enumeration, replay floods, or oversized payload DoS.
- **Controls:**
  - Each tenant is provisioned with a unique CSPRNG webhook secret (`whsec_...`), stored encrypted at rest, rotatable at any time via `POST /api/v1/settings/rotate-webhook-secret`.
  - Constant-time HMAC-SHA256 verification (`MessageDigest.isEqual`) against `X-Hub-Signature-256`.
  - Uniform `401 Unauthorized` on unknown slugs or invalid signatures (prevents tenant slug enumeration).
  - Per-IP webhook rate limiting + replay defense combining `X-GitHub-Delivery` and payload SHA-256 hash.
  - Inline diff payloads inside webhooks are disabled in the `prod` profile; GitHub API diff downloads enforce path traversal rejection (`.` / `..`) and a 1 MB size cap.

## 6. Encryption at Rest & Key Rotation
- **Threat:** Exposure of stored GitHub PATs or tenant webhook secrets via database dump or backup compromise.
- **Controls:**
  - `SecretCryptoService` uses **AES-256-GCM** with 96-bit CSPRNG IVs, 128-bit authentication tags, versioned envelope format `v1:<keyId>:<base64(iv||ciphertext)>`, and **Additional Authenticated Data (AAD)** binding `tenantId` and `purpose` (preventing cross-tenant ciphertext swapping).
  - Tampered ciphertexts or wrong keys throw `SecretDecryptionException` (never silently returning plaintext).
  - Zero-downtime key rotation is supported via `FORGE_ENCRYPTION_KEY_ID`, `FORGE_ENCRYPTION_KEY`, and comma-separated `FORGE_ENCRYPTION_PREVIOUS_KEYS` (`kid:key`), plus `reEncrypt(...)`.

## 7. Prompt Injection Defense & Secret Redaction
- **Threat:** Malicious PR diffs or comments instructing the LLM to ignore vulnerabilities, approve PRs, or exfiltrate system prompts; leaked secrets echoed in API responses or logs.
- **Controls:**
  - `SecurityScannerEngine` detects prompt-injection payloads (`FORGE-PROMPT-001`) and `AiReviewEngine` **quarantines** flagged lines before prompt construction, escapes boundary tags, and separates `SystemMessage` from untrusted `UserMessage` payloads.
  - Detected secrets undergo **multi-match per-line masking** and **full PEM private-key block redaction** before persistence, SARIF export, or API serialization.
  - `ChangeRiskEngine` evaluates deterministic policy rules (including `blockOnPromptInjection`) independently of LLM output, and human approval is mandatory before posting PR reviews to GitHub.
  - Untrusted repository source code is **never** compiled or executed on FORGE servers.
