# Inventory Service

## Responsibility
Tracks stock quantity per product. One inventory row per `productId`
(enforced unique). This service does not own product details (name, price,
etc.) — it only references `productId` as an opaque id, keeping it loosely
coupled from Product Service per `plan.md`'s "duplicate DTOs, don't share
domain models" rule.

## Current phase status
**Phase 9** (Ordering & Scaling) adds
`config/KafkaRebalanceConfig.java`, a `ContainerCustomizer` bean that
attaches a `ConsumerAwareRebalanceListener` to every `@KafkaListener`
container here, logging partition ASSIGNED/REVOKED at INFO level. Also
made `server.port` overridable via a `SERVER_PORT` env var so a second
instance can run alongside the first (`SERVER_PORT=8093 mvn
spring-boot:run`) without touching checked-in config. Both the ordering
guarantee (every producer already keys by `orderId`) and the scaling
behavior (Kafka splitting/reassigning this service's 6 partitions across
however many instances are in the `inventory-service` consumer group) were
already true before this phase — this phase's own code just makes
rebalancing visible in the logs, and the live test that proved it. See
[[ARCHITECTURE.md]]'s "Ordering & Scaling" section for the full write-up,
including the two-instance rebalance observation and the 4-order
correctness test.

Phase 8 (Idempotency) adds a `processed_events` table
(`entity/ProcessedEvent.java` + `repository/ProcessedEventRepository.java`)
and a `UUID eventId` field on every event this service produces/consumes.
`InventoryService.reserveAllAndPublish`/`releaseAllForOrder` now check
`processedEventRepository.existsById(incomingEventId)` — keyed on the
*incoming* `OrderCreatedEvent`/`OrderCancelledEvent`'s own id, not the
freshly-generated outgoing `InventoryReservedEvent`'s id — before touching
stock at all, so a redelivered/retried order event can't double-decrement
or double-release. Verified live: replaying the exact same
`OrderCreatedEvent` JSON left stock quantity completely unchanged. See
[[ARCHITECTURE.md]]'s "Idempotency" section for the full design.

Phase 7 (Retry & Dead Letter Queue) adds
`config/KafkaErrorHandlingConfig.java`: every `@KafkaListener` here now
retries a failing message up to 3 total attempts (1s then 2s backoff)
before it's routed to `<topic>.DLT` instead of retrying forever. The
consumer's `value-deserializer` in `application.yml` is now
`ErrorHandlingDeserializer` (wrapping the real `JsonDeserializer`) — see
order-service's README or [[ARCHITECTURE.md]] for why a plain
`JsonDeserializer` here would let a malformed message cause an unbounded
tight loop that bypasses this retry/DLQ machinery entirely.

Phase 6 (Transactional Outbox): `InventoryEventProducer` no longer calls
Kafka directly — it writes to a new `outbox_events` table
(`entity/OutboxEvent.java`) instead, published later by a `@Scheduled`
`event/OutboxPoller.java`. The reserve-then-publish-success path was moved
into `InventoryService.reserveAllAndPublish()` so the stock decrement and
the `InventoryReservedEvent` outbox row commit as one transaction — see "How
the outbox works now" below, including a real bug this surfaced and how it
was fixed (a naively `@Transactional` listener silently broke the
reservation-failure path).

Phase 4 (Event-Driven Workflow): this service now also consumes
`order.cancelled` to release stock it previously reserved (the compensating
action when payment fails after reservation succeeded — see order-service's
README), and publishes `NotificationRequestedEvent` alongside
`InventoryFailedEvent` when reservation itself fails, so Notification
Service doesn't need to listen to raw domain events anymore.

Phase 3 baseline (Kafka Integration): started consuming `order.created`
events from Kafka and reserving stock automatically — Order Service no
longer calls `/api/inventory/reserve` directly over HTTP.
Phase 2 (superseded): reserve/release were called synchronously by Order
Service's OpenFeign client — replaced by the Kafka listener below.

## Tech
- Spring Boot 3.3.4, Spring Data JPA, Bean Validation
- Spring Kafka (`spring-kafka`) — consumer for `order.created` /
  `order.cancelled`, producer for `inventory.reserved` / `inventory.failed`
  / `notification.requested`
- Database: PostgreSQL, schema `inventorydb` (own database)
- Port: `8083`

## Data model
| Field | Type | Notes |
|---|---|---|
| id | Long | PK, generated |
| productId | Long | required, unique (one inventory record per product) |
| quantity | Integer | required, >= 0 |

## Endpoints
Base path: `/api/inventory` (direct) or via gateway on port `8080`.

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/inventory` | `InventoryRequest` | 201 + `InventoryResponse` |
| GET | `/api/inventory` | – | 200 + `List<InventoryResponse>` |
| GET | `/api/inventory/{id}` | – | 200 + `InventoryResponse` / 404 |
| PUT | `/api/inventory/{id}` | `InventoryRequest` | 200 + `InventoryResponse` / 404 |
| DELETE | `/api/inventory/{id}` | – | 204 / 404 |
| POST | `/api/inventory/reserve` | `StockChangeRequest` | 200 + `InventoryResponse` / 404 / 409 |
| POST | `/api/inventory/release` | `StockChangeRequest` | 200 + `InventoryResponse` / 404 |

`InventoryRequest`: `{ "productId": number, "quantity": number >= 0 }`
`InventoryResponse`: `{ "id": number, "productId": number, "quantity": number }`

`StockChangeRequest` (used by `reserve`/`release`): `{ "productId": number, "quantity": number >= 1 }`
- `reserve`: decrements `quantity` by the requested amount. Uses a pessimistic
  write lock (`findWithLockByProductId`) so two concurrent reservations for
  the same product can't both read a stale quantity and oversell. Returns
  409 if available stock is less than requested (`InsufficientStockException`),
  404 if no inventory record exists for that `productId`.
- `release`: increments `quantity` back (compensating action used by Order
  Service when a downstream step fails after stock was already reserved).

Errors follow the shared `ApiError` shape.
- 404 when inventory id not found, or `productId` has no inventory record
- 409 when an inventory record for that `productId` already exists (on create), or stock is insufficient (on reserve)
- 400 with field-level `details` on validation failure

## How order reservation actually works now (Phase 4)

`event/OrderCreatedEventListener.java` consumes both `order.created` and
`order.cancelled` (consumer group `inventory-service`):
1. `onOrderCreated`: converts each `OrderCreatedEvent.Item` into a
   `StockChangeRequest` and calls `InventoryService.reserveAll(items)` — an
   all-or-nothing reservation: if any item fails (not found or insufficient
   stock), every item already reserved in that same call is released before
   the exception propagates, so a partially-fulfilled order never leaves
   stock partially decremented.
   - On success: publishes `InventoryReservedEvent` (orderId, userId,
     totalAmount) to `inventory.reserved`, picked up next by Payment Service.
   - On failure: publishes `InventoryFailedEvent` (orderId, userId, reason)
     to `inventory.failed` **and** `NotificationRequestedEvent` (orderId,
     userId, a pre-built "out of stock" message) to `notification.requested`
     — Order Service consumes `inventory.failed` to cancel the order,
     Notification Service consumes `notification.requested` to notify the
     user; neither needs to build the message itself anymore.
2. `onOrderCancelled` (new in Phase 4): order-service only publishes
   `OrderCancelledEvent` when this service HAD successfully reserved stock
   for that order (see order-service's README on `releaseInventory`) — so
   this listener releases every item in the event's `items` list
   unconditionally, no additional check needed. This is the compensating
   action for the case where inventory reservation succeeded but payment
   failed afterward.

## How the outbox works now (Phase 6)

`InventoryEventProducer.publish*()` methods now call
`OutboxEventService.enqueue(topic, key, event)` instead of
`KafkaTemplate.send()` directly — see order-service's README for the
general mechanism (outbox table + `@Scheduled` poller, same pattern in
every service).

**The one thing that's different here, and why**: the
`OrderCreatedEvent` listener (`event/OrderCreatedEventListener.onOrderCreated`)
has a try/reserve/catch/publish-failure shape — `reserveAll()` throws on
insufficient stock or an unknown product, and the `catch` block publishes
`InventoryFailedEvent` instead. Marking `onOrderCreated` itself
`@Transactional` (the first attempt) broke this: once `reserveAll()`
throws, Spring marks the *entire* transaction rollback-only, and that
poisons the outbox write for `InventoryFailedEvent` inside the `catch`
block too, even though the exception never escapes the listener method.
The result in testing was silent — no exception, no stack trace overflow —
just the same `OrderCreatedEvent` being redelivered and reprocessed
indefinitely, because the DB state recording "this failed" never actually
committed.

The fix: `onOrderCreated` itself is **not** `@Transactional`.
`InventoryService.reserveAllAndPublish(items, reservedEvent)` is a new
method that runs `reserveAll()` and `eventProducer.publishReserved()` as one
atomic transaction (success path only). The listener's `catch` block runs
with no surrounding transaction at all, so `publishFailed()` and
`publishNotificationRequested()` each commit independently via
`OutboxEventService`'s own (non-`@Transactional`, so per-call) save —
regardless of how the try block's transaction resolved.

`onOrderCancelled` (the stock-release compensating action) doesn't publish
any event at all, so it keeps its own `@Transactional` safely — there's no
catch-and-continue-publishing path there to poison.

## Kafka topics this service produces/consumes
| Topic | Direction | Payload | Consumer group (this service) |
|---|---|---|---|
| `order.created` | consumes | `OrderCreatedEvent` | `inventory-service` |
| `order.cancelled` | consumes | `OrderCancelledEvent` | `inventory-service` |
| `inventory.reserved` | produces | `InventoryReservedEvent` | – |
| `inventory.failed` | produces | `InventoryFailedEvent` | – |
| `notification.requested` | produces | `NotificationRequestedEvent` | – (shared topic — payment-service also produces onto it) |

Event classes live in `event/` and are this service's own local copies
(not shared with order-service or payment-service) — see order-service's
README for why. As of Phase 4 this service listens to two topics/event
types, so each `@KafkaListener` in `OrderCreatedEventListener` overrides
`spring.json.value.default.type` per-listener instead of relying on one
consumer-factory-wide default.

## How other services find this service
No service registry yet. Discovery is now entirely via Kafka topic names
(plain string constants in `event/KafkaTopics.java`) plus the shared broker
address (`spring.kafka.bootstrap-servers: localhost:9092`) — there is no
longer any inbound HTTP call from Order Service to this service.
- External clients still reach this service's REST CRUD/reserve/release
  endpoints through the **API Gateway** (port `8080`), which routes
  `Path=/api/inventory/**` to `http://localhost:8083` — unchanged from
  earlier phases, this is just no longer how Order Service talks to it.

## Communication style
- **Client → this service**: synchronous REST/JSON (CRUD +
  reserve/release), via the gateway or directly on port `8083`.
- **Order Service → this service**: fully asynchronous, via Kafka
  (`order.created` in, `inventory.reserved`/`inventory.failed` out). No more
  direct HTTP call from Order Service.
