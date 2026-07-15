# Notification Service

## Responsibility
Records notifications intended for a user (e.g., order confirmation emails).
Stores `userId` as a plain reference (no call to User Service). Does not
actually send email/SMS in Phase 1 — it only persists the notification
record and its status; real delivery integration is a later-phase concern.

## Current phase status
**Phase 3** (Kafka Integration): this service now consumes the three
possible final outcomes of an order — `payment.successful`,
`payment.failed`, `inventory.failed` — and automatically creates a
notification record for the user. It is the last stage of the Phase 3
event chain (Order → Inventory → Payment → Notification) and does not
depend on or wait for Order Service's own listener; both consume the same
events independently. Delivery itself (actually sending an email/SMS) is
still not implemented — same as Phase 1/2, this only persists the
notification's intent.

Phase 1 baseline: CRUD REST API + Postgres persistence, no Kafka consumer.

## Tech
- Spring Boot 3.3.4, Spring Data JPA, Bean Validation
- Spring Kafka (`spring-kafka`) — consumer only (this service never
  produces events)
- Database: PostgreSQL, schema `notificationdb` (own database)
- Port: `8086`

## Data model
| Field | Type | Notes |
|---|---|---|
| id | Long | PK, generated |
| userId | Long | required, references a user by id only |
| message | String | required |
| type | NotificationType | enum: `EMAIL`, `SMS` |
| status | NotificationStatus | enum: `PENDING`, `SENT`, `FAILED` |

## Endpoints
Base path: `/api/notifications` (direct) or via gateway on port `8080`.

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/notifications` | `NotificationRequest` | 201 + `NotificationResponse` |
| GET | `/api/notifications` | – | 200 + `List<NotificationResponse>` |
| GET | `/api/notifications/{id}` | – | 200 + `NotificationResponse` / 404 |
| PUT | `/api/notifications/{id}` | `NotificationRequest` | 200 + `NotificationResponse` / 404 |
| DELETE | `/api/notifications/{id}` | – | 204 / 404 |

`NotificationRequest`: `{ "userId": number, "message": string, "type": "EMAIL"|"SMS", "status": "PENDING"|"SENT"|"FAILED" }`
`NotificationResponse`: `{ "id": number, "userId": number, "message": string, "type": string, "status": string }`

Errors follow the shared `ApiError` shape.
- 404 when notification id not found
- 400 with field-level `details` on validation failure

## How notification creation actually works now (Phase 3)

`event/OrderOutcomeEventListener.java` has three separate `@KafkaListener`
methods, one per topic (all in consumer group `notification-service`):
- `payment.successful` → creates an `EMAIL`/`PENDING` notification: "Your
  order #N has been confirmed. Amount charged: X"
- `payment.failed` → creates an `EMAIL`/`PENDING` notification: "Your
  order #N was cancelled: payment failed (reason)"
- `inventory.failed` → creates an `EMAIL`/`PENDING` notification: "Your
  order #N was cancelled: item(s) out of stock (reason)"

Each event carries `userId` (threaded through from `OrderCreatedEvent` at
the start of the chain, through every intermediate event, specifically so
this service doesn't need to call User/Order Service to figure out who to
notify).

## Kafka topics this service produces/consumes
| Topic | Direction | Payload | Consumer group (this service) |
|---|---|---|---|
| `payment.successful` | consumes | `PaymentSuccessfulEvent` | `notification-service` |
| `payment.failed` | consumes | `PaymentFailedEvent` | `notification-service` |
| `inventory.failed` | consumes | `InventoryFailedEvent` | `notification-service` |

This service produces no topics of its own — it's a pure sink at the end of
the chain. Event classes live in `event/` and are this service's own local
copies (not shared with the producing services).

## How other services find this service
No service registry yet. This service doesn't need to be *found* by
anyone — nothing calls it directly, over HTTP or otherwise. It finds *them*:
discovery is via Kafka topic names (plain string constants in
`event/KafkaTopics.java`) plus the shared broker address
(`spring.kafka.bootstrap-servers: localhost:9092`).
- External clients (Postman, manual testing) still reach this service's
  REST CRUD endpoints through the **API Gateway** (port `8080`), which
  routes `Path=/api/notifications/**` to `http://localhost:8086`.

## Communication style
- **Client → this service**: synchronous REST/JSON (CRUD), via the gateway
  or directly on port `8086` — unchanged from Phase 1.
- **Other services → this service**: fully asynchronous, via Kafka. This
  service only ever consumes; it never produces or calls anything.
