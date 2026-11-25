# FORGE Testing & Operational Runbook

## Running Locally (Zero Docker Required)
1. **Backend (Java 21 LTS + Spring Boot 3.5, `local` profile with H2 PG-mode + Flyway + Outbox Worker):**
   ```powershell
   cd backend
   .\mvnw.cmd spring-boot:run
   ```
   - API Base: `http://localhost:8080/api/v1`
   - Swagger / OpenAPI UI: `http://localhost:8080/swagger-ui.html`
   - Prometheus Metrics: `http://localhost:8080/actuator/prometheus`
   - Demo Workspace Credentials (pre-seeded on first local boot):
     - Email: `nischay@forge.dev`
     - Password: `ForgeUser@2026`
     - Role: `OWNER`

2. **Frontend (React + TypeScript + Vite):**
   ```powershell
   cd frontend
   npm install
   npm run dev
   ```
   - App URL: `http://localhost:5173` (proxies `/api` and `/actuator` to `http://localhost:8080`)

## Running Full Production Stack (Docker Compose)
```bash
docker compose up --build
```
Starts PostgreSQL 16 (pgvector), Apache Kafka (KRaft), Redis 7, Prometheus (`:9090`), Grafana (`:3001`), FORGE Backend (`:8080`), and FORGE Frontend (`:5173`).

## Running All Automated Tests
- **Backend (Unit + Integration + Security + ArchUnit):**
  ```powershell
  cd backend
  .\mvnw.cmd test
  ```
- **Frontend (TypeScript + Vitest + Production Bundle):**
  ```powershell
  cd frontend
  npm run typecheck
  npm run test
  npm run build
  ```
