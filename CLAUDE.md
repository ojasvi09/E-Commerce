# CLAUDE.md

This file orients a fresh Claude session on this project. Read this first,
then `plan.md` (the authoritative phase-by-phase spec), then `ARCHITECTURE.md`
(current cross-cutting system state) before making any changes.

## What this project is

A learning project: build an event-driven e-commerce platform incrementally
to learn Microservices + Apache Kafka, one phase at a time, with a git
commit after each completed milestone. This is **not** a production system —
it deliberately favors clarity and learning value over completeness (e.g.
simulated payments, no real email delivery, no auth yet).

- Source of truth for scope: **`plan.md`** (derived from `project.txt`) —
  12 phases (0–11), each with a specific deliverable. Do not skip ahead or
  invent scope beyond the current phase's bullet points.
- Source of truth for current system state: **`ARCHITECTURE.md`** — services
  table, Kafka topics table, communication patterns, "planned evolution"
  section. Regenerated at the end of every phase.
- Per-service detail: each service's own `README.md` (responsibility,
  endpoints, data model, how it talks to other services, current phase
  status). Also regenerated at the end of every phase.
- Per-service `postman_collection.json` for manual testing with realistic
  request bodies.

## Where we are

**Phases 0–4 complete, committed, and pushed to `origin/main`** (branch
`main`, remote `https://github.com/ojasvi09/E-Commerce.git`). Most recent
commit at time of writing: `7fc27cc` (Phase 4).

| Phase | Status | One-line summary |
|---|---|---|
| 0 – Scaffolding | Done | Multi-module Maven, git init, docker-compose skeleton |
| 1 – Core Microservices | Done | 7 services, CRUD REST, own Postgres DB each |
| 2 – Synchronous Communication | Done, then superseded | OpenFeign + Resilience4j Order→Inventory→Payment — fully removed in Phase 3 |
| 3 – Kafka Integration | Done | Order→Inventory→Payment→Notification converted to Kafka producer/consumer |
| 4 – Event-Driven Workflow | Done | Full domain event set (`OrderCancelled`, `ShipmentCreated`, `NotificationRequested`, `RefundInitiated`) + compensating actions (stock release, refund) |
| 5 – Saga Pattern | **Not started** | Formal choreography saga w/ defined compensations; optional orchestrator |
| 6 – Transactional Outbox | Not started | Outbox table per service so DB write + publish stay atomic |
| 7 – Retry & DLQ | Not started | Exponential backoff + dead-letter topic per consumer group |
| 8 – Idempotency | Not started | `processed_events` table per consumer — **known live gap today**, see below |
| 9 – Ordering & Scaling | Partially done early | Already keying by `orderId` since Phase 3; multi-instance-consumer scaling not yet exercised |
| 10 – Security | Not started | JWT auth, RBAC at the gateway |
| 11 – Docker (full) | Not started | Compose currently only runs infra, not the services themselves |

**Known gap already surfaced by live testing** (see `ARCHITECTURE.md`
"Planned evolution"): there is no idempotency guard yet, so a redelivered
`order.cancelled` message would double-release stock or double-refund. This
is expected — it's exactly what Phase 8 exists to fix — but a future
session should not be surprised by it.

## Services (all 7, fixed since Phase 1 — do not add new services without discussion)

| Service | Port | Database |
|---|---|---|
| api-gateway | 8080 | – |
| user-service | 8081 | `userdb` |
| product-service | 8082 | `productdb` |
| inventory-service | 8083 | `inventorydb` |
| order-service | 8084 | `orderdb` |
| payment-service | 8085 | `paymentdb` |
| notification-service | 8086 | `notificationdb` |

Database-per-service: no service reads/writes another's tables. Services
reference each other only by plain numeric id (`userId`, `orderId`, ...),
never by cross-database foreign key.

## Current communication pattern (as of Phase 4)

Order placement is **fully asynchronous via Kafka** — no HTTP calls remain
between order/inventory/payment/notification. `user-service` and
`product-service` are not part of this chain.

Full topic list, producers/consumers, and payload shapes are documented in
`ARCHITECTURE.md`'s "Kafka topics" table — read that instead of trusting
this summary to stay current, since it's regenerated every phase and this
file is not.

Key conventions to preserve in future phases:
- **Event DTOs are duplicated per service**, not shared via a common JAR —
  same loose-coupling rule as REST DTOs. Each service keeps its own local
  copy of every event it produces or consumes, under its own `event/`
  package.
- **JSON deserialization**: `spring.json.use.type.headers: false` in every
  service's `application.yml`, plus `spring.json.value.default.type` set
  either at the consumer-factory level (single-topic consumers) or
  per-`@KafkaListener` via its `properties` attribute (multi-topic
  consumers) — never trust the producer's package name.
- **Consumer groups**: one per service, named after the service itself
  (`order-service`, `inventory-service`, etc.) — every service gets its own
  independent copy of every event on a topic it subscribes to.
- **Message keys**: every producer keys by `orderId.toString()` so all
  events for one order land on the same partition and process in order.
- **Topics**: 3 partitions, replication factor 1 (single-broker dev setup),
  declared via `NewTopic` beans in `config/KafkaTopicConfig.java` in
  whichever service produces that topic.

## Infra

`docker-compose.yml` at the repo root runs: Postgres 16-alpine, Zookeeper +
Kafka (Confluent 7.6.1, dual listener — `kafka:29092` internal /
`localhost:9092` host-facing), Redis 7-alpine, and `kafka-ui`
(provectuslabs/kafka-ui, host port **8090**) — this project's own Kafka UI,
not a shared/stray one. Services themselves are **not** in Docker yet
(that's Phase 11) — run them locally via `mvn spring-boot:run`.

## How to run and test (Windows/PowerShell + Git Bash environment)

1. `docker compose up -d` (from repo root) — brings up Postgres/Kafka/
   Zookeeper/Redis/kafka-ui.
2. Start each service: `cd <service-dir> && mvn spring-boot:run`. To
   capture logs reliably instead of relying on shell redirection, pass
   `-Dspring-boot.run.arguments="--logging.file.name=<path>"`.
3. Use each service's `postman_collection.json`, or curl directly, or go
   through the gateway on port 8080.
4. Kafka UI at `http://localhost:8090` to inspect topics/messages/consumer
   lag/partition assignment.
5. Windows process management gotcha: bash-tracked PIDs from `run_in_background`
   are not the real `java.exe` PIDs. To find and kill a service bound to a
   port: `netstat -ano | grep LISTENING | grep :<port>` then
   `taskkill //F //PID <pid>`.

## Working rules for this project (established across prior sessions — follow these without being re-asked)

1. **Never commit or push until functionality has been verified live** —
   services actually running, real HTTP requests made, real responses
   observed, not just "it compiles." This has been followed strictly every
   phase so far (manual curl tests, log inspection for Kafka event traces,
   DB record checks, and even manual Kafka message injection via
   `kafka-console-producer` to exercise compensating-action code paths that
   don't naturally trigger otherwise).
2. **One git commit per completed phase/milestone**, with a message
   explaining what changed and why, not just what files changed.
3. **Regenerate all affected READMEs + `ARCHITECTURE.md` + Postman
   collections at the end of every phase**, before committing. Docs drift
   is treated as a bug.
4. When a phase's description leaves a real design decision open (e.g.
   which HTTP client, what failure semantics, sync vs. async response
   shape, topic granularity, whether to add a new service or fold
   functionality into an existing one), make the call and proceed —
   default to whatever best fits this project's existing conventions
   (loose coupling, duplicated DTOs, database-per-service, choreography
   over central coordination until Phase 5 says otherwise). Only stop and
   ask if the decision is genuinely ambiguous or materially changes scope
   (e.g. adding an 8th service) — do not ask permission to write code that
   was already requested.
5. Don't add speculative abstractions, tests infrastructure, or
   cross-cutting concerns (auth, outbox, DLQ, idempotency) ahead of the
   phase that calls for them — each of those is its own numbered phase for
   a reason. It's fine to note a gap in `ARCHITECTURE.md`'s "Planned
   evolution" section when live testing surfaces one, without fixing it
   early.
6. Explain Kafka/Spring/Feign concepts in depth when asked, grounded in the
   actual code just written, not generic textbook descriptions.

## Suggested next step

**Phase 5 (Saga Pattern)**: Phase 4 already implemented choreography-style
compensating actions (Inventory releases stock, Payment refunds) triggered
by `OrderCancelledEvent`. Phase 5's real remaining scope is formalizing this
as a named saga and optionally adding an orchestrator service for
comparison against the choreography approach already in place. Confirm
design specifics (orchestrator or choreography-only, saga state tracking
approach) before implementing if `plan.md`'s bullet points leave it open.
