# ADR-001: Modular Monolith with Transactional Outbox & Async Kafka Workers

- **Status:** Accepted
- **Context:** FORGE requires strong domain cohesion across 10 engineering modules (Identity, Repositories, Code Intelligence, PR Review, Security, CI, Analytics, Audit, Messaging, SSE) while supporting asynchronous long-running review and indexing pipelines.
- **Decision:** Build a single Spring Boot 3.5 modular monolith with strict package boundaries enforced by **ArchUnit**, PostgreSQL as single source of truth, a **Transactional Outbox** (`outbox_events`), and **Apache Kafka** consumers with idempotency (`processed_events`) and Dead-Letter Topics (`forge.pr-review.dlt`). Provide a `local` Spring profile with H2 (PostgreSQL mode) and an in-process outbox dispatcher so developers can run and verify the complete pipeline with zero Docker overhead.
- **Consequences:** Avoids premature distributed microservice complexity while preserving clean async boundaries so heavy AST/AI workers can be extracted into separate deployable containers later with minimal refactoring.
