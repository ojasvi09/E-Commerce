# Payment Service

## Responsibility
Records payment attempts/results against an order. Stores `orderId` as a
plain reference (no call to Order Service) plus the amount and status of the
payment. Does not process real payments in Phase 1 — status is set directly
by the caller (no payment gateway integration yet).

## Current phase status
**Phase 4** (Event-Driven Workflow): this service now also consumes
`order.cancelled` — if it finds a `SUCCESSFUL` payment on file for that
order, it publishes `RefundInitiatedEvent` (simulated refund, same "no real
gateway" caveat as charging itself). It also publishes
`NotificationRequestedEvent` alongside `PaymentSuccessfulEvent`/
`PaymentFailedEvent`, so Notification Service doesn't need to listen to raw
domain events anymore.

Phase 3 baseline (Kafka Integration): started consuming `inventory.reserved`
events and charging payment automatically — Order Service no longer calls
`POST /api/payments` directly over HTTP.
Phase 2 (superseded): Order Service called `POST /api/payments` directly
via OpenFeign — replaced by the Kafka listener below.

## Tech
- Spring Boot 3.3.4, Spring Data JPA, Bean Validation
- Spring Kafka (`spring-kafka`) — consumer for `inventory.reserved` /
  `order.cancelled`, producer for `payment.successful` / `payment.failed` /
  `refund.initiated` / `notification.requested`
- Database: PostgreSQL, schema `paymentdb` (own database)
- Port: `8085`

## Data model
| Field | Type | Notes |
|---|---|---|
| id | Long | PK, generated |
| orderId | Long | required, references an order by id only |
| amount | BigDecimal | required, >= 0 |
| status | PaymentStatus | enum: `PENDING`, `SUCCESSFUL`, `FAILED` |

## Endpoints
Base path: `/api/payments` (direct) or via gateway on port `8080`.

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/payments` | `PaymentRequest` | 201 + `PaymentResponse` |
| GET | `/api/payments` | – | 200 + `List<PaymentResponse>` |
| GET | `/api/payments/{id}` | – | 200 + `PaymentResponse` / 404 |
| PUT | `/api/payments/{id}` | `PaymentRequest` | 200 + `PaymentResponse` / 404 |
| DELETE | `/api/payments/{id}` | – | 204 / 404 |

`PaymentRequest`: `{ "orderId": number, "amount": number >= 0, "status": "PENDING"|"SUCCESSFUL"|"FAILED" }`
`PaymentResponse`: `{ "id": number, "orderId": number, "amount": number, "status": string }`

Errors follow the shared `ApiError` shape.
- 404 when payment id not found
- 400 with field-level `details` on validation failure

## How charging actually works now (Phase 4)

`event/InventoryReservedEventListener.java` consumes `inventory.reserved`
(consumer group `payment-service`):
1. Calls `PaymentService.create(...)` directly (in-process, not over HTTP)
   with `status: SUCCESSFUL` for the order's total amount — same simulated
   "always succeeds unless persistence itself throws" behavior as earlier
   phases, there's still no real payment gateway to actually fail against.
2. On success: publishes `PaymentSuccessfulEvent` (orderId, userId, amount)
   to `payment.successful`, plus `NotificationRequestedEvent` with a
   pre-built confirmation message to `notification.requested`.
3. On failure (an unexpected exception, e.g. a DB error): publishes
   `PaymentFailedEvent` (orderId, userId, reason) to `payment.failed`, plus
   `NotificationRequestedEvent` with a pre-built failure message.

`payment.successful`/`payment.failed` are consumed by Order Service (to
mark the order `CONFIRMED`/`CANCELLED`) — this service doesn't know or care
who's listening.

`event/OrderCancelledEventListener.java` consumes `order.cancelled` (new in
Phase 4, consumer group `payment-service`): order-service only publishes
this event when inventory HAD already been reserved for the order (i.e.
this service already tried to charge it — see order-service's README on
`releaseInventory`). If a `SUCCESSFUL` payment exists for that `orderId`,
publishes `RefundInitiatedEvent` (orderId, userId, amount, reason) to
`refund.initiated` — a compensating action, simulated the same way charging
itself is. If no successful payment is found (e.g. the cancellation came
from `payment.failed` itself), does nothing — there's nothing to refund.

## Kafka topics this service produces/consumes
| Topic | Direction | Payload | Consumer group (this service) |
|---|---|---|---|
| `inventory.reserved` | consumes | `InventoryReservedEvent` | `payment-service` |
| `order.cancelled` | consumes | `OrderCancelledEvent` | `payment-service` |
| `payment.successful` | produces | `PaymentSuccessfulEvent` | – |
| `payment.failed` | produces | `PaymentFailedEvent` | – |
| `refund.initiated` | produces | `RefundInitiatedEvent` | – |
| `notification.requested` | produces | `NotificationRequestedEvent` | – (shared topic — inventory-service also produces onto it) |

Event classes live in `event/` and are this service's own local copies
(not shared with inventory-service or order-service). As of Phase 4 this
service listens to two topics/event types, so each `@KafkaListener`
overrides `spring.json.value.default.type` per-listener instead of one
consumer-factory-wide default.

## How other services find this service
No service registry yet. Discovery is now entirely via Kafka topic names
(plain string constants in `event/KafkaTopics.java`) plus the shared broker
address (`spring.kafka.bootstrap-servers: localhost:9092`) — there is no
longer any inbound HTTP call from Order Service to this service.
- External clients still reach this service's REST CRUD endpoints through
  the **API Gateway** (port `8080`), which routes `Path=/api/payments/**`
  to `http://localhost:8085` — unchanged from earlier phases.

## Communication style
- **Client → this service**: synchronous REST/JSON (CRUD), via the gateway
  or directly on port `8085`.
- **Inventory Service → this service**: fully asynchronous, via Kafka
  (`inventory.reserved` in, `payment.successful`/`payment.failed` out). No
  more direct HTTP call from Order Service.
