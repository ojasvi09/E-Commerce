# Architecture Overview

This file is the top-level map of the system. It is meant to be
**regenerated/updated at the end of every phase** in `plan.md`, since the
system's structure and cross-cutting concerns evolve materially from phase
to phase per `plan.md`/`project.txt`.

> Last updated: **Phase 5** (Saga Pattern — order-service now records the
> choreography's progress per order in a new `saga_state` table, queryable
> via `GET /api/orders/{id}/saga`; no orchestrator, no change to how
> inventory-service/payment-service behave). Phase 4 added the full domain
> event set: `OrderCancelled`, `ShipmentCreated`, `NotificationRequested`,
> `RefundInitiated` on top of Phase 3's `OrderCreated`/`InventoryReserved`/
> `InventoryFailed`/`PaymentSuccessful`/`PaymentFailed`.
> See each service's own `README.md` for full detail — this file only
> summarizes and cross-links.

## Services

| Service | Port | Database | Responsibility |
|---|---|---|---|
| [user-service](user-service/README.md) | 8081 | `userdb` | User accounts |
| [product-service](product-service/README.md) | 8082 | `productdb` | Product catalog |
| [inventory-service](inventory-service/README.md) | 8083 | `inventorydb` | Stock levels per product |
| [order-service](order-service/README.md) | 8084 | `orderdb` | Orders + order items |
| [payment-service](payment-service/README.md) | 8085 | `paymentdb` | Payment records per order |
| [notification-service](notification-service/README.md) | 8086 | `notificationdb` | Notification records per user |
| [api-gateway](api-gateway/README.md) | 8080 | – | Single entry point, routes to the 6 services above |

Each service owns its own Postgres database (see
`infra/postgres/init-databases.sql`) — no service reads or writes another
service's tables directly. This is the "database-per-service" pattern:
services only reference each other by plain numeric id
(`userId`, `productId`, `orderId`, …), never by foreign key constraint across
databases.

## How services communicate today (Phase 4)

**Order placement is fully asynchronous, event-driven via Kafka** — no
change from Phase 3 in that respect. Phase 4 adds the rest of the domain
event set (`OrderCancelled`, `ShipmentCreated`, `NotificationRequested`,
`RefundInitiated`) and, with it, two real compensating actions
(compensating actions in the sense Phase 5's formal saga will build on —
this phase is still choreography without a coordinator). `user-service` and
`product-service` remain completely uninvolved in this chain, exactly as in
every earlier phase.

**Order placement flow** (`POST /api/orders` → eventual `CONFIRMED`/
`CANCELLED`, see each service's own README for full detail):

1. **Order Service** persists the order as `CREATED` and publishes
   `OrderCreatedEvent` to topic `order.created`, then returns immediately.
   The HTTP response body always shows `status: "CREATED"` — the client
   must poll `GET /api/orders/{id}` afterward to see the real outcome.
2. **Inventory Service** consumes `order.created`, tries to reserve stock
   for every line item (all-or-nothing: any single item failing releases
   everything already reserved for that order). Publishes
   `inventory.reserved` on success; on failure, publishes `inventory.failed`
   **and** `notification.requested` (with a pre-built "out of stock"
   message) — nothing was reserved, so there's nothing to compensate.
3. **Payment Service** consumes `inventory.reserved`, charges the order
   total (simulated — no real payment gateway, same as every earlier
   phase). Publishes `payment.successful` or `payment.failed`, plus
   `notification.requested` either way.
4. **Order Service** consumes `inventory.failed`, `payment.successful`, and
   `payment.failed` to update the order's final status:
   - `inventory.failed` → `CANCELLED`, no further event published (nothing
     was reserved).
   - `payment.failed` → `CANCELLED`, **and** publishes `OrderCancelledEvent`
     (with the order's items) to `order.cancelled` — inventory WAS reserved
     before payment was attempted, so this is the signal to compensate.
   - `payment.successful` → `CONFIRMED`, plus creates a `Shipment` record
     and publishes `ShipmentCreatedEvent` to `shipment.created` (nothing
     consumes this yet — it exists so fulfillment has a defined starting
     event to build on later).
5. **Inventory Service** separately consumes `order.cancelled` and releases
   the stock listed in the event — the compensating action for a
   successful reservation whose order was cancelled downstream.
6. **Payment Service** separately consumes `order.cancelled`; if it finds a
   `SUCCESSFUL` payment for that order, publishes `RefundInitiatedEvent` to
   `refund.initiated` (simulated refund). In the current flow this never
   actually fires for the `payment.failed` cancellation path (there's no
   successful payment to refund in that case) — it's there for any future
   cancellation source that arrives after a successful charge.
7. **Notification Service** consumes a single topic, `notification.requested`,
   built by whichever service (Inventory or Payment) decided the user needs
   telling something. It no longer listens to raw domain events directly —
   see notification-service's README for why.

This is still choreography, not orchestration: each service reacts to
events relevant to it and decides its own compensating action
independently. Phase 5 formalizes this choreography as a named saga by
having **order-service** record the sequence's progress in a `saga_state`
table (one row per orderId, steps: `STARTED` → `PAYMENT_PROCESSED` →
`SHIPMENT_CREATED` → `COMPLETED` on the success path, or `STARTED` →
`COMPENSATING` → `FAILED` on the payment-failure path, or straight to
`FAILED` on the inventory-failure path since nothing was reserved) —
queryable via `GET /api/orders/{id}/saga`. This is a pure observability
layer: it doesn't sequence or coordinate anything, and no orchestrator
service was added (that remains optional per `plan.md` and was not built
this phase). See order-service's own README for the full step semantics,
including why there's no `INVENTORY_RESERVED` step (order-service never
observes a positive inventory-reserved signal, only the failure case).

## Kafka topics

| Topic | Producer | Consumers | Payload |
|---|---|---|---|
| `order.created` | order-service | inventory-service | `OrderCreatedEvent` (orderId, userId, totalAmount, items) |
| `order.cancelled` | order-service | inventory-service, payment-service | `OrderCancelledEvent` (orderId, userId, reason, items) |
| `shipment.created` | order-service | *(none yet)* | `ShipmentCreatedEvent` (orderId, userId, shipmentId) |
| `inventory.reserved` | inventory-service | payment-service | `InventoryReservedEvent` (orderId, userId, totalAmount) |
| `inventory.failed` | inventory-service | order-service | `InventoryFailedEvent` (orderId, userId, reason) |
| `payment.successful` | payment-service | order-service | `PaymentSuccessfulEvent` (orderId, userId, amount) |
| `payment.failed` | payment-service | order-service | `PaymentFailedEvent` (orderId, userId, reason) |
| `refund.initiated` | payment-service | *(none yet)* | `RefundInitiatedEvent` (orderId, userId, amount, reason) |
| `notification.requested` | inventory-service, payment-service | notification-service | `NotificationRequestedEvent` (orderId, userId, message) |

Each topic has 3 partitions, replication factor 1 (single-broker dev
setup), declared via `NewTopic` beans (`config/KafkaTopicConfig.java`) in
whichever service produces that topic — Spring Boot auto-creates them on
startup if they don't already exist. `notification.requested` is declared
by both inventory-service and payment-service (both produce onto it);
duplicate `NewTopic` beans for the same name/partition count are harmless,
Kafka just no-ops on the second one. Every producer keys messages by
`orderId.toString()`, so all events for one order land on the same
partition and are processed in order by a given consumer instance.

**Event DTOs are duplicated per service**, not shared via a common JAR —
same loose-coupling rule as the REST DTOs in Phase 1/2. Each service has
its own local copy of every event class it produces or consumes, under its
own `event/` package. JSON (de)serialization is configured with
`spring.json.use.type.headers: false` in every service's `application.yml`,
so a consumer never relies on the *producing* service's package name (which
it has no copy of) — instead each `@KafkaListener` (or the consumer factory
default, for single-topic consumers) explicitly declares
`spring.json.value.default.type` pointing at its own local event class.

**Consumer groups**: each service has its own consumer group named after
itself (`order-service`, `inventory-service`, `payment-service`,
`notification-service`) — this is deliberate, not incidental: it means
every service gets its own independent copy of every event on a topic it
subscribes to (e.g. both `order-service` and `notification-service` fully
consume `payment.successful`, neither steals messages from the other,
because they're different consumer groups).

## How services discover each other

There is **no service registry** (Eureka/Consul) in this project. Discovery
now has two distinct forms:

- **External clients → services**: unchanged from Phase 1/2 — through the
  API Gateway (`api-gateway`, port `8080`), which has a hardcoded route
  table mapping `/api/{resource}/**` path prefixes to each service's
  `localhost:<port>`. See [api-gateway/README.md](api-gateway/README.md).
  The gateway itself is untouched through Phase 4 — client-facing traffic
  is still synchronous REST.
- **Service → service**: no longer HTTP-based at all for the
  Order/Inventory/Payment/Notification chain (as of Phase 3, extended in
  Phase 4). Every service points at the same Kafka broker
  (`spring.kafka.bootstrap-servers: localhost:9092`, hardcoded in each
  `application.yml` — still no registry, just one shared address instead of
  many per-service ones) and "discovers" what it needs by topic name (plain
  string constants in each service's own `event/KafkaTopics.java`). There
  is no `services.inventory.url` / `services.payment.url` anywhere in the
  codebase — those were removed along with the Feign clients in Phase 3.

## Planned evolution (do not implement yet — tracked here for context only)

Per `plan.md`/`project.txt`, later phases are expected to add:
- An orchestrator/coordinator service, for comparison against today's
  choreography-only saga (Phase 5's "optional" bullet, deliberately not
  built this phase) — each service still decides its own compensating
  action independently; the new `saga_state` table only records progress,
  it doesn't sequence or coordinate anything
- Transactional outbox per service so DB writes and event publishing stay
  atomic (Phase 6) — right now, e.g., Inventory Service's stock reservation
  and its `inventory.reserved`/`inventory.failed` publish are two separate
  operations that could in principle diverge if the process crashes between
  them
- Retry with backoff + dead-letter topics on consumers (Phase 7)
- Idempotency via a `processed_events` table per consumer (Phase 8) — right
  now, a redelivered message (e.g. after a consumer restart mid-processing)
  would be reprocessed with no de-duplication. This is a real gap already:
  the manual `order.cancelled` compensating-action test during Phase 4
  development showed a redelivery would double-release stock or
  double-refund with no guard in place today
- Spring Security / auth on the gateway and services (Phase 10)
- Redis caching
- Testcontainers-based integration tests
- Docker Compose bringing up the services themselves (Phase 11) — today's
  `docker-compose.yml` only runs infra (Postgres, Kafka, Zookeeper, Redis),
  not the Spring Boot apps
- A real Shipment domain (carrier, tracking number, delivery status) and
  possibly its own microservice — Phase 4 only added a minimal `Shipment`
  record inside order-service to give `ShipmentCreatedEvent` somewhere to
  come from; nothing consumes that event yet

When any of the above lands, update this file's "Services" table status line
and the "How services communicate" / "discover" sections above — do not let
this document drift from the code.

## Postman collections

Each service has a `postman_collection.json` in its own directory covering
its CRUD endpoints with realistic request bodies (see each service's
`README.md` for the exact request/response shapes). Import the collection
for the specific service you're testing, or hit the same paths through the
gateway on port `8080` instead of the service's own port.
