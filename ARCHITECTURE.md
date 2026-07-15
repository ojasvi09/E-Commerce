# Architecture Overview

This file is the top-level map of the system. It is meant to be
**regenerated/updated at the end of every phase** in `plan.md`, since the
communication pattern between services (currently plain REST) is expected to
change materially once Kafka is introduced (Phase 5+ per `plan.md`/`project.txt`).

> Last updated: **Phase 3** (Order → Inventory → Payment → Notification
> converted from Phase 2's synchronous OpenFeign calls to a fully
> asynchronous Kafka producer/consumer chain). See each service's own
> `README.md` for full detail — this file only summarizes and cross-links.

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

## How services communicate today (Phase 3)

**Order placement is now fully asynchronous, event-driven via Kafka.**
Phase 2's synchronous OpenFeign calls (Order → Inventory, Order → Payment)
have been **removed entirely** — there is no more direct HTTP call between
any of these four services. `user-service` and `product-service` remain
completely uninvolved in this chain, exactly as in Phase 1/2 (their ids are
still just opaque numbers referenced by other services).

**Order placement flow** (`POST /api/orders` → eventual `CONFIRMED`/
`CANCELLED`, see each service's own README for full detail):

1. **Order Service** persists the order as `CREATED` and publishes
   `OrderCreatedEvent` to topic `order.created`, then returns immediately.
   The HTTP response body always shows `status: "CREATED"` — the client
   must poll `GET /api/orders/{id}` afterward to see the real outcome.
2. **Inventory Service** consumes `order.created`, tries to reserve stock
   for every line item (all-or-nothing: any single item failing releases
   everything already reserved for that order). Publishes
   `inventory.reserved` on success, `inventory.failed` on failure.
3. **Payment Service** consumes `inventory.reserved`, charges the order
   total (simulated — no real payment gateway, same as every earlier
   phase). Publishes `payment.successful` or `payment.failed`.
4. **Order Service** separately consumes `inventory.failed`,
   `payment.successful`, and `payment.failed` to update the order's final
   status: `CONFIRMED` on `payment.successful`, `CANCELLED` on either
   failure topic.
5. **Notification Service** independently consumes the same three outcome
   topics (`inventory.failed`, `payment.successful`, `payment.failed`) and
   creates a notification record for the user — it does not wait for or
   depend on Order Service's own listener; both react to the same events
   in parallel.

This is a straight-line async pipeline, not yet a formal saga with defined
compensating transactions per step (that's Phase 5's scope) — Phase 3's
goal was specifically "convert the chain to producer/consumer," which this
is.

## Kafka topics

| Topic | Producer | Consumers | Payload |
|---|---|---|---|
| `order.created` | order-service | inventory-service | `OrderCreatedEvent` (orderId, userId, totalAmount, items) |
| `inventory.reserved` | inventory-service | payment-service | `InventoryReservedEvent` (orderId, userId, totalAmount) |
| `inventory.failed` | inventory-service | order-service, notification-service | `InventoryFailedEvent` (orderId, userId, reason) |
| `payment.successful` | payment-service | order-service, notification-service | `PaymentSuccessfulEvent` (orderId, userId, amount) |
| `payment.failed` | payment-service | order-service, notification-service | `PaymentFailedEvent` (orderId, userId, reason) |

Each topic has 3 partitions, replication factor 1 (single-broker dev
setup), declared via `NewTopic` beans (`config/KafkaTopicConfig.java`) in
whichever service produces that topic — Spring Boot auto-creates them on
startup if they don't already exist. Every producer keys messages by
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
  The gateway itself is untouched by Phase 3 — client-facing traffic is
  still synchronous REST.
- **Service → service**: as of Phase 3, this is no longer HTTP-based at
  all for the Order/Inventory/Payment/Notification chain. Every service
  points at the same Kafka broker (`spring.kafka.bootstrap-servers:
  localhost:9092`, hardcoded in each `application.yml` — still no registry,
  just one shared address instead of many per-service ones) and "discovers"
  what it needs by topic name (plain string constants in each service's own
  `event/KafkaTopics.java`). There is no longer a `services.inventory.url` /
  `services.payment.url` anywhere in the codebase — those were removed
  along with the Feign clients.

## Planned evolution (do not implement yet — tracked here for context only)

Per `plan.md`/`project.txt`, later phases are expected to add:
- The full domain event set beyond what Phase 3 needed (Phase 4):
  `OrderCancelled`, `ShipmentCreated`, `NotificationRequested`,
  `RefundInitiated`, and moving *all* inter-service business communication
  onto Kafka as the primary channel (Phase 3 only converted this one chain)
- A formal choreography-based saga with compensating actions (Phase 5)
- Transactional outbox per service so DB writes and event publishing stay
  atomic (Phase 6) — right now, e.g., Inventory Service's stock reservation
  and its `inventory.reserved`/`inventory.failed` publish are two separate
  operations that could in principle diverge if the process crashes between
  them
- Retry with backoff + dead-letter topics on consumers (Phase 7)
- Idempotency via a `processed_events` table per consumer (Phase 8) — right
  now, a redelivered message (e.g. after a consumer restart mid-processing)
  would be reprocessed with no de-duplication
- Spring Security / auth on the gateway and services (Phase 10)
- Redis caching
- Testcontainers-based integration tests
- Docker Compose bringing up the services themselves (Phase 11) — today's
  `docker-compose.yml` only runs infra (Postgres, Kafka, Zookeeper, Redis),
  not the Spring Boot apps

When any of the above lands, update this file's "Services" table status line
and the "How services communicate" / "discover" sections above — do not let
this document drift from the code.

## Postman collections

Each service has a `postman_collection.json` in its own directory covering
its CRUD endpoints with realistic request bodies (see each service's
`README.md` for the exact request/response shapes). Import the collection
for the specific service you're testing, or hit the same paths through the
gateway on port `8080` instead of the service's own port.
