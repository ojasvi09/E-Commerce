# Architecture Overview

This file is the top-level map of the system. It is meant to be
**regenerated/updated at the end of every phase** in `plan.md`, since the
system's structure and cross-cutting concerns evolve materially from phase
to phase per `plan.md`/`project.txt`.

> Last updated: **Phase 9** (Ordering & Scaling — every topic was already
> keyed by `orderId` and already had 3 partitions since Phase 3/6, so this
> phase mainly *proved* the ordering/scaling guarantees live: ran two
> inventory-service instances in the same consumer group, watched Kafka
> split partitions across them, watched partitions reassign back to the
> survivor when one instance stopped, and confirmed real orders processed
> exactly once each with no double-decrement regardless of which instance
> handled them). Phase 8 added idempotency: every event record now carries a
> random `eventId` (UUID), and all 4 consumer services check their own
> `processed_events` table before doing any mutating work, so a
> redelivered/retried event — from Kafka consumer-group redelivery, Phase
> 7's retries, or a Phase 6 outbox-poller double-send — is a safe no-op
> instead of double-decrementing stock, double-charging a payment, or
> sending a duplicate notification). Phase 7 added retry & DLQ: every
> `@KafkaListener` across all 4 consumer services retries a failing message
> up to 3 total attempts with exponential backoff, then routes it to a
> `<topic>.DLT` topic instead of retrying forever; deserialization failures
> skip straight to the DLQ. Phase 6 added the transactional outbox:
> order-service, inventory-service, and payment-service each write every
> Kafka event they produce to their own `outbox_events` table in the SAME
> database transaction as the domain write that caused it, instead of
> calling `KafkaTemplate.send()` directly, with a `@Scheduled` `OutboxPoller`
> in each service doing the actual send. Phase 5 added order-service's
> `saga_state` table, queryable via `GET /api/orders/{id}/saga`. Phase 4
> added the full domain event set: `OrderCancelled`, `ShipmentCreated`,
> `NotificationRequested`, `RefundInitiated` on top of Phase 3's
> `OrderCreated`/`InventoryReserved`/`InventoryFailed`/`PaymentSuccessful`/
> `PaymentFailed`.
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

## Transactional outbox (Phase 6)

Every producer in order-service, inventory-service, and payment-service now
writes to Kafka indirectly, through its own `outbox_events` table, instead
of calling `KafkaTemplate.send()` inline:

1. A domain method (e.g. `OrderService.create()`, `InventoryService.reserveAllAndPublish()`,
   `PaymentService.chargeAndPublish()`) does its normal entity save, then
   calls `<Service>EventProducer.publish*()`, which serializes the event to
   JSON and inserts an `OutboxEvent` row (`topic`, `event_key`, `payload`,
   `created_at`, `published_at = null`) — **in the same `@Transactional`
   method**, so the domain write and the outbox row commit or roll back
   together. This is the actual guarantee this phase adds: previously (Phase
   3-5) the Kafka send happened after the transaction, with no such
   guarantee — a crash between the two could silently lose the event or
   publish one for a write that got rolled back.
2. A `@Scheduled` `OutboxPoller` (default every 500ms, `outbox.poll-interval-ms`)
   in each service polls its own `outbox_events` for unpublished rows
   (`findTop50ByPublishedAtIsNullOrderByIdAsc`), sends each to Kafka via the
   same `KafkaTemplate`, and marks `published_at` on success. A row that
   fails to send is simply retried on the next poll (still `published_at IS
   NULL`) — there's no separate retry/backoff/DLQ bookkeeping yet, that's
   Phase 7.
3. The poller parses the stored JSON payload back into a plain `Map`
   (not the original event record class) before sending — `JsonSerializer`
   produces identical wire JSON for a `Map` as for the record, and every
   consumer already deserializes purely from JSON shape
   (`spring.json.use.type.headers: false`), ignoring type headers. So this
   is wire-compatible with every existing `@KafkaListener` unchanged.

**A transaction-boundary pitfall found during Phase 6 testing, and the fix
applied**: naively marking `@KafkaListener` methods `@Transactional` (so the
domain write and outbox row commit together) breaks the *catch* branch of
any listener that does try/reserve-or-charge/catch/publish-failure-event.
Once the try block's operation throws, Spring marks the **entire**
transaction rollback-only — even after the exception is caught, any outbox
row written afterward in that same transaction (e.g. `InventoryFailedEvent`,
`PaymentFailedEvent`) silently never commits. Worse, no exception escapes
the listener method, so Spring Kafka doesn't know anything went wrong and
the message can end up redelivered indefinitely with no visible error
&mdash; this was caught live (see inventory-service's
`OrderCreatedEventListener` and payment-service's
`InventoryReservedEventListener` javadoc for the full story) before being
fixed. The fix: the listener methods themselves are **not** `@Transactional`.
The success path's domain-write-plus-outbox-write is its own atomic unit,
moved into the service layer (`InventoryService.reserveAllAndPublish()`,
`PaymentService.chargeAndPublish()`); the listener's `catch` block runs with
no surrounding transaction at all, so each failure-path
`eventProducer.publish*()` call commits independently, regardless of how
the try block's transaction resolved.

## Retry & Dead Letter Queue (Phase 7)

Before this phase, an uncaught exception in any `@KafkaListener` (something
genuinely unexpected — not a modeled business failure like "insufficient
stock," which already has its own `*Failed` event path) caused Spring
Kafka's default behavior: retry the same message forever, immediately, with
no backoff, blocking every later message on that partition indefinitely.
Phase 7 replaces that with a bounded retry-then-dead-letter policy, added
identically to every consumer service (order-service, inventory-service,
payment-service, notification-service):

1. **`config/KafkaErrorHandlingConfig.java`** (new in each service) defines
   a `DefaultErrorHandler` bean with an `ExponentialBackOff`: 1s initial
   delay, ×2 multiplier, `setMaxAttempts(2)` — meaning 1 initial attempt +
   2 retries = **3 total attempts**, roughly 1s then 2s apart. (Spring's
   `BackOff#setMaxAttempts` counts *retries*, not total attempts — this
   tripped us up during testing: `setMaxAttempts(3)` actually produced 4
   total attempts, not 3. Confirmed live and corrected before this phase's
   commit.) Once attempts are exhausted, a `DeadLetterPublishingRecoverer`
   (constructed with the service's own `KafkaTemplate`) republishes the
   message to `<originalTopic>.DLT` — Spring Kafka's default DLQ naming
   convention — instead of retrying forever, so the consumer can move on to
   the next message. Spring Boot's autoconfigured
   `ConcurrentKafkaListenerContainerFactory` picks up this bean
   automatically; no changes needed to any existing `@KafkaListener` method
   or business logic.
2. **Deserialization failures are classified as non-retryable**
   (`errorHandler.addNotRetryableExceptions(SerializationException.class,
   DeserializationException.class)`) — a malformed/unparseable message can
   never succeed no matter how many times the same bytes are retried, so
   those go straight to the DLQ without wasting the backoff delay.
   Everything else (e.g. a transient DB error thrown from inside a
   listener) still gets the full 3-attempt retry treatment.

**A second real bug found and fixed during Phase 7 testing, more severe
than the one above**: the non-retryable-exception classification only
covers exceptions thrown *during listener invocation* — it has no effect on
a malformed message when the consumer's value-deserializer is a plain
`JsonDeserializer`, because that throws inside `KafkaConsumer.poll()`
itself, *before* the listener or `DefaultErrorHandler` ever runs. Live
testing confirmed this: injecting one malformed JSON message onto
`payment.failed` caused order-service to re-poll and fail on the exact same
record in a **tight, unbounded loop with no backoff at all** — worse than
the very problem this phase exists to fix, and it doesn't stop on its own
(had to be killed manually; over 34,000 failed attempts accumulated in
under a minute). The fix: every service's `application.yml` now sets
`spring.kafka.consumer.value-deserializer` to Spring Kafka's
`ErrorHandlingDeserializer`, with the real `JsonDeserializer` configured as
its delegate via `spring.deserializer.value.delegate.class`. This makes a
deserialization failure surface as a normal
`DeserializationException` *inside* the listener-container's error-handling
path, where `DefaultErrorHandler` already classifies it as non-retryable
and dead-letters it immediately — confirmed live: the previously-stuck
poison message was consumed, dead-lettered, and the consumer's offset
advanced past it on the very next restart, with zero retries and zero
delay.

**Scope note**: this phase only covers consumer-side (`@KafkaListener`)
failures. The Phase 6 outbox poller's own retry loop (an unpublished row is
retried every poll tick, indefinitely, with no cap) is a separate,
producer-side concern and was deliberately left unchanged this phase.

## Idempotency (Phase 8)

Before this phase, every consumer was genuinely non-idempotent: a
redelivered `OrderCreatedEvent` would double-decrement stock, a redelivered
`InventoryReservedEvent` would insert a second `Payment` row (double-charge),
a redelivered `PaymentSuccessfulEvent` would create a duplicate `Shipment`
row, and so on for every listener. This was a real, not theoretical, risk —
Phase 7's retries mean a listener can now be invoked up to 3 times per
message, and the Phase 6 outbox poller has its own independent
redelivery path (a send can reach the broker but crash before the
`publishedAt` row commits, causing the next poll tick to resend the same
outbox row under a new Kafka offset).

**How it works**: every event record across all three producer services
(order-service, inventory-service, payment-service — `OrderCreatedEvent`,
`OrderCancelledEvent`, `ShipmentCreatedEvent`, `InventoryReservedEvent`,
`InventoryFailedEvent`, `PaymentSuccessfulEvent`, `PaymentFailedEvent`,
`RefundInitiatedEvent`, `NotificationRequestedEvent`) now carries a random
`UUID eventId`, generated once via `UUID.randomUUID()` at the call site
that builds the event (e.g. `OrderService.create`,
`InventoryService.reserveAllAndPublish`, `PaymentService.chargeAndPublish`).
Since the outbox write is just `objectMapper.writeValueAsString(event)` on
the whole record, the id flows into the JSON payload and onto the wire with
no changes needed to the outbox table or `OutboxPoller` — consumers already
deserialize purely from JSON shape, so the new field just appears.

Each of the 4 consumer services has its own `processed_events` table
(`entity/ProcessedEvent.java` + `repository/ProcessedEventRepository.java`,
same pattern as `SagaState`/`OutboxEvent` — plain JPA entity, no migration
tooling) keyed by `eventId` (not Kafka topic/partition/offset — deliberately,
since a resent outbox row gets a *new* offset but the *same* eventId, so
keying on eventId covers both the Kafka-redelivery case and the
outbox-double-send case with one mechanism). Every mutating method that
handles an incoming event now:
1. Checks `processedEventRepository.existsById(incomingEventId)` first —
   if already processed, logs and returns immediately, doing nothing.
2. Does its normal work (stock reserve/release, payment charge, order
   status update, notification create, refund publish) — unchanged from
   Phases 4-7.
3. Saves a `ProcessedEvent` row for `incomingEventId`, in the SAME
   transaction as step 2, right before returning.

This is deliberately **inline in each existing method**, not a generic
`@Idempotent` annotation or AOP aspect — consistent with how the outbox and
saga-state patterns were added in Phases 5-6 (explicit, hand-rolled code
per call site rather than a new abstraction). The guarded methods:
`InventoryService.reserveAllAndPublish`/`releaseAllForOrder`,
`PaymentService.chargeAndPublish`, `OrderCancelledEventListener.onOrderCancelled`
(payment-service, refund path — guarded inline since it has no domain
mutation to house a service method around), `OrderService.markConfirmed`/
`markCancelled`, `NotificationService.createIfNotProcessed`.

Note the dedupe key each guard uses is always the *incoming* event's
`eventId`, never a freshly-minted outgoing event's id (which is different
on every call and would never repeat, making it useless as a dedupe key).

Verified live: replaying the exact same `OrderCreatedEvent` JSON (same
`eventId`) that inventory-service had already processed logged "Skipping
already-processed OrderCreatedEvent" and left stock quantity completely
unchanged — no double-decrement. Same confirmed for a redelivered
`payment.failed` event against order-service (no duplicate
`OrderCancelledEvent`/refund triggered a second time).

## Ordering & Scaling (Phase 9)

`plan.md` asks for two things: (1) key messages by `orderId` for per-order
ordering guarantees, (2) run multiple consumer instances per group and
observe partition assignment/rebalancing. Both prerequisites turned out to
already be in place from earlier phases, so this phase was mostly about
**proving** the guarantees live rather than writing new domain code.

**Ordering guarantee (already true since Phase 3/6, verified this phase)**:
every producer (`OrderEventProducer`, `InventoryEventProducer`,
`PaymentEventProducer`) calls `OutboxEventService.enqueue(topic, eventKey,
event)` with `event.orderId().toString()` as the key, and `OutboxPoller`
forwards that key unchanged to `kafkaTemplate.send(topic, key, value)`. Kafka
hashes the key to deterministically choose a partition, so every event for a
given `orderId` always lands on the same partition — and a single partition
is always consumed strictly in order by whichever single consumer owns it.
This means an `OrderCreated` and a later `OrderCancelled` for the same order
can never be processed out of order, even under retries/rebalancing.
Every topic (`order.created`, `order.cancelled`, `inventory.reserved`,
`inventory.failed`, `payment.successful`, `payment.failed`,
`refund.initiated`, `shipment.created`, `notification.requested`) is declared
with 3 partitions via `TopicBuilder.name(...).partitions(3).replicas(1)` in
each producing service's `config/KafkaTopicConfig.java` — nothing relies on
Kafka's broker-level `auto.create.topics.enable` default of 1 partition.

**Scaling test (new this phase)**: added
`inventory-service/.../config/KafkaRebalanceConfig.java`, a
`ContainerCustomizer` bean that attaches a `ConsumerAwareRebalanceListener`
to every `@KafkaListener` container, logging `partitions ASSIGNED`/`partitions
REVOKED` at INFO level — purely observational, doesn't change delivery
semantics. Also made `server.port` overridable via a `SERVER_PORT` env var
(`${SERVER_PORT:8083}` in `application.yml`) so a second instance can run
alongside the first without touching checked-in config.

Ran two `inventory-service` instances (port 8083 and 8093, `SERVER_PORT=8093
mvn spring-boot:run`), same `inventorydb`, same consumer group
(`inventory-service`). Live observations:
- **Single instance startup**: one instance holds all 6 partitions across its
  two listener containers (3× `order.created`, 3× `order.cancelled`).
- **Second instance joins**: both listener containers on both instances see
  `partitions REVOKED` then `partitions ASSIGNED` — Kafka splits the 3
  partitions per topic roughly evenly: instance A ends up with 1 partition
  per topic, instance B with 2 per topic (`RangeAssignor` default behavior
  for 3 partitions over 2 consumers).
- **Second instance stops**: both containers on the survivor see
  `partitions REVOKED` then reassigned **all** partitions back — the
  surviving instance ends up holding all 6 partitions again, exactly like
  the single-instance starting state.
- **Correctness under 2 live instances**: placed 4 real orders (ids 26-29)
  through the full order → inventory → payment → notification chain while
  both instances were running. All 4 reached `CONFIRMED`; stock for the
  ordered product dropped by exactly 4 (91 from 95, one decrement per
  order — no double-processing). Cross-checking each instance's logs showed
  order 29 was handled by the instance owning that order's partition and
  orders 26-28 by the other instance — each order processed exactly once, by
  whichever instance happened to own its partition, confirming horizontal
  scaling doesn't compromise the per-order guarantees from Phases 6-8.

No new abstraction was needed for the ordering half (already correct), and
the scaling half is Kafka's own consumer-group mechanism — this project adds
no custom partition-assignment or leader-election logic, just observability
into what Kafka is already doing.

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
  action independently; the `saga_state` table only records progress, it
  doesn't sequence or coordinate anything
- A cap on the Phase 6 outbox poller's own retry loop — right now an
  unpublished outbox row is retried every poll tick (500ms) indefinitely
  with no limit, no backoff, and no dead-letter equivalent for a row that
  can never successfully send (e.g. a permanently misconfigured topic).
  Phase 7 deliberately scoped its retry/DLQ work to consumer-side
  (`@KafkaListener`) failures only and left this producer-side case
  unchanged — worth a future look if a real stuck-row problem shows up
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
