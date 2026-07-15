# Inventory Service

## Responsibility
Tracks stock quantity per product. One inventory row per `productId`
(enforced unique). This service does not own product details (name, price,
etc.) — it only references `productId` as an opaque id, keeping it loosely
coupled from Product Service per `plan.md`'s "duplicate DTOs, don't share
domain models" rule.

## Current phase status
**Phase 4** (Event-Driven Workflow): this service now also consumes
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
