# Order Service

## Responsibility
Owns orders and their line items. An `Order` has one `userId` (foreign key
reference only, no call to User Service) and a list of `OrderItem` children,
each carrying its own `productId`, `quantity`, and `price` snapshot at order
time (so historical orders aren't affected by later product price changes).
`totalAmount` is computed server-side as the sum of `price * quantity` across
all items.

## Current phase status
**Phase 5** (Saga Pattern) adds a `SagaState` entity (`saga_state` table,
one row per orderId) that records the choreography's current step —
`STARTED`, `PAYMENT_PROCESSED`, `SHIPMENT_CREATED`, `COMPENSATING`,
`COMPLETED`, `FAILED` — so a saga's overall progress is queryable via
`GET /api/orders/{id}/saga` instead of only inferable by cross-referencing
Order/Inventory/Payment records separately. This is choreography-only (no
orchestrator service) — see "How the saga is tracked now" below for why and
what it does/doesn't cover.

Phase 4 (Event-Driven Workflow) extends Phase 3's chain with two new
events this service produces: `OrderCancelledEvent` (published only when
inventory had already been reserved for the order, so Inventory Service can
release it — see "How order placement works now" below) and
`ShipmentCreatedEvent` (published once an order is confirmed, alongside a
new lightweight `Shipment` record persisted here). There is no dedicated
Shipment microservice — Phase 1 fixed the service list at 7, so this service
owns the minimal shipment record since it already owns order lifecycle
state.

Phase 3 baseline (Kafka Integration): replaced Phase 2's synchronous
OpenFeign calls entirely — placing an order publishes `OrderCreatedEvent`
and returns immediately with status `CREATED`; Inventory/Payment/
Notification react asynchronously.
Phase 2 (superseded): synchronous OpenFeign + Resilience4j — removed in
Phase 3, kept here in history only as context for why some patterns exist
(e.g. `OrderStatus.CREATED` as a real, now much more visible, intermediate
state).

## Tech
- Spring Boot 3.3.4, Spring Data JPA, Bean Validation
- Spring Kafka (`spring-kafka`) — producer for `order.created` /
  `order.cancelled` / `shipment.created`, consumer for `inventory.failed` /
  `payment.successful` / `payment.failed`
- Database: PostgreSQL, schema `orderdb` (own database)
- Port: `8084`
- `Order` 1:N `OrderItem` via JPA `@OneToMany(cascade = ALL, orphanRemoval = true)`
- `Shipment` — new in Phase 4, one row per confirmed order (`orderId`,
  `createdAt`), no relationship back to `Order` beyond the plain id
  reference (same loose-coupling convention as everywhere else)

## Data model
**Order**
| Field | Type | Notes |
|---|---|---|
| id | Long | PK, generated |
| userId | Long | required, references a user by id only |
| status | OrderStatus | enum: `CREATED`, `CONFIRMED`, `CANCELLED` |
| totalAmount | BigDecimal | computed from items, not client-supplied |
| items | List\<OrderItem\> | child entities, cascade all / orphan removal |

**OrderItem** (child of Order)
| Field | Type | Notes |
|---|---|---|
| id | Long | PK, generated |
| productId | Long | required, references a product by id only |
| quantity | Integer | required, >= 1 |
| price | BigDecimal | required, >= 0 (snapshot, not looked up live) |

## Endpoints
Base path: `/api/orders` (direct) or via gateway on port `8080`.

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/orders` | `OrderRequest` | 201 + `OrderResponse` |
| GET | `/api/orders` | – | 200 + `List<OrderResponse>` |
| GET | `/api/orders/{id}` | – | 200 + `OrderResponse` / 404 |
| GET | `/api/orders/{id}/saga` | – | 200 + `SagaStateResponse` / 404 (Phase 5) |
| PUT | `/api/orders/{id}` | `OrderRequest` | 200 + `OrderResponse` / 404 (replaces item list) |
| DELETE | `/api/orders/{id}` | – | 204 / 404 |

`OrderRequest`:
```json
{
  "userId": 1,
  "items": [
    { "productId": 10, "quantity": 2, "price": 19.99 }
  ]
}
```
`OrderResponse` — **note the status is now always `CREATED` immediately
after `POST`**, not `CONFIRMED`/`CANCELLED` like Phase 2:
```json
{
  "id": 1,
  "userId": 1,
  "status": "CREATED",
  "totalAmount": 39.98,
  "items": [
    { "id": 1, "productId": 10, "quantity": 2, "price": 19.99 }
  ]
}
```
Poll `GET /api/orders/{id}` afterward to observe it transition to
`CONFIRMED` or `CANCELLED` once the async chain finishes (typically well
under a second locally, but there is no guaranteed latency — that's the
nature of eventual consistency).

Errors follow the shared `ApiError` shape.
- 404 when order id not found
- 400 with field-level `details` on validation failure (e.g., empty `items`)

## How order placement actually works now (Phase 4)

`OrderService.create()`:
1. Builds the `Order` + `OrderItem`s and persists it as `CREATED`.
2. Publishes an `OrderCreatedEvent` (orderId, userId, totalAmount, items) to
   the `order.created` Kafka topic, keyed by `orderId` (so all events for
   one order land on the same partition and are processed in order).
3. Returns the `CREATED` order immediately — this method no longer waits
   for inventory/payment at all.

Downstream, asynchronously:
4. **Inventory Service** consumes `order.created`, tries to reserve stock
   for every line item (all-or-nothing — see its own README), and publishes
   either `inventory.reserved` or `inventory.failed` (+ `NotificationRequestedEvent`
   on failure).
5. **Payment Service** consumes `inventory.reserved`, charges the order
   total, and publishes either `payment.successful` or `payment.failed`
   (+ `NotificationRequestedEvent` either way).
6. **This service** listens for all three possible outcomes via
   `event/OrderEventListener.java`:
   - `inventory.failed` → `OrderService.markCancelled(orderId, reason, releaseInventory=false)`.
     Inventory reservation never happened (or was already rolled back
     internally by `reserveAll`), so **no** `OrderCancelledEvent` is
     published here — there's nothing to release.
   - `payment.failed` → `OrderService.markCancelled(orderId, reason, releaseInventory=true)`.
     Inventory WAS reserved before payment was attempted, so this publishes
     `OrderCancelledEvent` (orderId, userId, reason, items) to
     `order.cancelled` — Inventory Service consumes it and releases the
     stock; Payment Service consumes it too and issues a refund if it finds
     a `SUCCESSFUL` payment for that order (it won't in this exact case,
     since payment just failed, but the same event covers any future
     cancellation source that arrives after a successful charge).
   - `payment.successful` → `OrderService.markConfirmed()`, which also
     creates a `Shipment` row and publishes `ShipmentCreatedEvent`
     (orderId, userId, shipmentId) to `shipment.created` — the start of
     fulfillment. Nothing consumes this yet (no shipment/tracking consumer
     exists); it exists so the event is defined and fired per Phase 4's
     requirement, ready for a future consumer.
7. **Notification Service** consumes a single `NotificationRequestedEvent`
   stream (topic `notification.requested`) built by whichever service
   decided the user needs telling something — it no longer listens to the
   raw outcome events directly (see notification-service's README).

Phase 4 established this as choreography-style: each service reacts to the
events relevant to it and decides its own compensating action
(Inventory releases stock, Payment refunds) without any central
orchestrator directing the sequence.

## How the saga is tracked now (Phase 5)

Phase 5 formalizes the above choreography by recording it as a named saga,
without adding an orchestrator or changing how inventory-service/
payment-service behave at all — this is purely an observability layer on
top of Phase 4's existing event flow, built in `service/SagaStateService.java`:

- `OrderService.create()` calls `sagaStateService.start(orderId)` right
  after publishing `OrderCreatedEvent` → step `STARTED`.
- `OrderEventListener.onPaymentSuccessful()` (via `markConfirmed`) advances
  through `PAYMENT_PROCESSED` → `SHIPMENT_CREATED` → `COMPLETED`.
- `OrderEventListener.onPaymentFailed()` (via `markCancelled(..., releaseInventory=true)`)
  advances to `COMPENSATING` (inventory release / possible refund about to
  happen downstream) then `FAILED` — order-service isn't notified when the
  compensating actions themselves finish, so `FAILED` here means "this
  service's own work in the saga is done," not "the entire saga, including
  downstream compensation, has physically completed."
- `OrderEventListener.onInventoryFailed()` (via `markCancelled(..., releaseInventory=false)`)
  advances straight to `FAILED` — nothing was reserved, so there's no
  compensating step to run.

There is deliberately **no `INVENTORY_RESERVED` step**: order-service never
consumes a positive "inventory reserved" event (only `inventory.failed`, on
the failure path), so it has no way to observe that transition — inventory-
service's own stock records are the source of truth for that step. This
saga view is scoped to what order-service itself can see.

Still **no orchestrator/coordinator** — each service continues to decide its
own compensating action independently, exactly as in Phase 4. An
orchestrator (Phase 5's "optional" bullet) was deliberately not built this
phase; choreography-only was the chosen scope.

`SagaStateResponse`:
```json
{
  "orderId": 16,
  "currentStep": "COMPENSATING",
  "reason": "Payment failed: simulated gateway timeout",
  "updatedAt": "2026-07-15T05:25:37.133Z"
}
```

## Kafka topics this service produces/consumes
| Topic | Direction | Payload | Consumer group (this service) |
|---|---|---|---|
| `order.created` | produces | `OrderCreatedEvent` | – (n/a, this service produces it) |
| `order.cancelled` | produces | `OrderCancelledEvent` | – (n/a, this service produces it) |
| `shipment.created` | produces | `ShipmentCreatedEvent` | – (n/a, this service produces it) |
| `inventory.failed` | consumes | `InventoryFailedEvent` | `order-service` |
| `payment.successful` | consumes | `PaymentSuccessfulEvent` | `order-service` |
| `payment.failed` | consumes | `PaymentFailedEvent` | `order-service` |

All event classes live in `event/` and are duplicated per service (not
shared) — see [[ARCHITECTURE.md]]'s note on why, same loose-coupling rule as
the DTOs in earlier phases. JSON deserialization is configured with
`spring.json.use.type.headers: false` and a per-listener
`spring.json.value.default.type` override, so each service deserializes
into its own local copy of the event class regardless of which package the
producing service used.

## How other services find this service
Still no service registry (Eureka/Consul) — that remains out of scope.
Discovery is now entirely via Kafka topic names (plain string constants in
each service's own `event/KafkaTopics.java`) plus the shared broker address
(`spring.kafka.bootstrap-servers: localhost:9092` in every service's
`application.yml`) — there is no longer any hardcoded service-to-service
URL anywhere in this service (Phase 2's `services.inventory.url` /
`services.payment.url` properties are gone).
- External clients still reach this service through the **API Gateway**
  (port `8080`), which routes `Path=/api/orders/**` to `http://localhost:8084`.
  The gateway itself is unaffected by this phase — it's still synchronous
  REST for client-facing traffic.

## Communication style
- **Client → this service**: synchronous REST/JSON, via the gateway or
  directly on port `8084`, unchanged from Phase 1/2.
- **This service ↔ other services**: fully asynchronous, event-driven via
  Kafka. No more direct HTTP calls to Inventory or Payment Service.
