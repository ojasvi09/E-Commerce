# Plan – Event-Driven E-Commerce Platform

Derived from `project.txt`. Goal: learn Microservices + Apache Kafka by building a
production-style e-commerce platform incrementally, one phase at a time, with a
git commit after each completed milestone.

## Current State

Repo is an empty Eclipse Java skeleton (`.project`, `.classpath`, `src/module-info.java`).
No build tool (Maven/Gradle), no services, no Docker setup yet. First real task before
Phase 1 can start is scaffolding.

## Phase 0 – Project Scaffolding (prerequisite, not in project.txt but required to start)

* Decide build tool: Maven (multi-module) recommended for this stack.
* Create a multi-module root `pom.xml` with one module per service.
* Replace/remove the stray Eclipse `module-info.java` (conflicts with a multi-module
  non-modular Spring Boot setup) once the build tool is chosen.
* Initialize git repo (`git init`) so "commit after every milestone" is possible.
* Add `docker-compose.yml` skeleton for Postgres/Kafka/Redis (filled in progressively
  in later phases).

## Phase 1 – Core Microservices

* Scaffold 7 independent Spring Boot services, each with its own PostgreSQL DB:
  API Gateway, User, Product, Inventory, Order, Payment, Notification.
* Each service: REST API, DTOs + Bean Validation, global exception handling
  (`@ControllerAdvice`), Spring Data JPA entities/repositories, Dockerfile.
* Deliverable: each service runs standalone, exposes CRUD/basic endpoints, has its
  own DB schema, and is containerized.

## Phase 2 – Synchronous Communication

* Wire Order → Inventory → Payment via OpenFeign or WebClient.
* Add timeouts, retries, and Resilience4j circuit breakers on these calls.
* Deliverable: a placed order synchronously reserves inventory and charges payment,
  with graceful degradation when a downstream service is slow/down.

## Phase 3 – Kafka Integration

* Stand up Kafka (Docker Compose) with topics/partitions.
* Convert the Order→Inventory→Payment→Notification chain to producer/consumer flow
  with JSON serialization, explicit consumer groups, and offset handling.

## Phase 4 – Event-Driven Workflow

* Define the full domain event set: OrderCreated, InventoryReserved, InventoryFailed,
  PaymentSuccessful, PaymentFailed, OrderCancelled, ShipmentCreated,
  NotificationRequested, RefundInitiated.
* Move all inter-service business communication onto Kafka as the primary channel.

## Phase 5 – Saga Pattern

* Implement choreography-based saga for Order→Reserve Inventory→Process Payment→
  Create Shipment, with compensating actions (release inventory, cancel order) on
  payment failure.
* Optional: add an orchestrator service for comparison.

## Phase 6 – Transactional Outbox

* Add an outbox table per service; publish to Kafka from the outbox (poller or
  Debezium CDC, Debezium optional) so DB writes and event publishing stay atomic.

## Phase 7 – Retry & Dead Letter Queue

* Add retry with exponential backoff on consumers; route exhausted messages to a DLQ
  topic per consumer group.

## Phase 8 – Idempotency

* Add a `processed_events` table per consumer; make consumers idempotent against
  redelivery/duplicate processing.

## Phase 9 – Ordering & Scaling

* Key messages by `orderId` for per-order ordering guarantees.
* Run multiple consumer instances per group; observe partition assignment and
  rebalancing behavior.

## Phase 10 – Security

* JWT authentication, role-based authorization, centralized auth enforcement at the
  API Gateway.

## Phase 11 – Docker

* Full `docker-compose.yml` bringing up all services + Kafka + Postgres + Redis as
  one system; smoke-test the entire event flow end-to-end.

## Cross-Cutting (applies to every phase)

* Unit tests (JUnit 5, Mockito) and integration tests (Testcontainers) per service.
* One git commit per completed milestone, not per phase — keep commits small.
* Keep services loosely coupled; prefer duplicating a small DTO over sharing a
  domain model across service boundaries.
* Document *why* a pattern was introduced (e.g. why outbox, why saga) alongside the
  code, per the "understand why" principle in project.txt.

## Suggested Immediate Next Step

Phase 0 scaffolding → Phase 1, starting with the Order Service and Inventory Service
(the two the rest of the flow depends on first), or the API Gateway if you want
routing in place from the start.
