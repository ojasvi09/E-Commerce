# Notification Service

## Responsibility
Records notifications intended for a user (e.g., order confirmation emails).
Stores `userId` as a plain reference (no call to User Service). Does not
actually send email/SMS in Phase 1 — it only persists the notification
record and its status; real delivery integration is a later-phase concern.

## Current phase status
**Phase 4** (Event-Driven Workflow): this service no longer listens to raw
domain events directly. Instead, every producing service (Inventory,
Payment) builds its own user-facing message and publishes a single
`NotificationRequestedEvent` to `notification.requested` — this service
just consumes that one topic and creates a notification record. This keeps
Notification Service a dumb sink regardless of how many upstream
services/reasons exist to notify a user, and centralizes "what does the
user see" in the service that knows the actual outcome, not here.
Delivery itself (actually sending an email/SMS) is still not implemented —
this only persists the notification's intent.

Phase 3 baseline (Kafka Integration): consumed `payment.successful`,
`payment.failed`, `inventory.failed` directly, building the message itself
per event type — superseded by the single-topic approach above.
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

## How notification creation actually works now (Phase 4)

`event/NotificationRequestedEventListener.java` has a single
`@KafkaListener` method on `notification.requested` (consumer group
`notification-service`). It creates an `EMAIL`/`PENDING` notification from
whatever `message` string the producing service already built:
- **Inventory Service** publishes this when stock reservation fails:
  "Your order #N was cancelled: item(s) out of stock (reason)"
- **Payment Service** publishes this on both charge outcomes: "Your order
  #N has been confirmed. Amount charged: X" or "Your order #N was
  cancelled: payment failed (reason)"

Each event carries `userId` (threaded through from `OrderCreatedEvent` at
the start of the chain, through every intermediate event, specifically so
this service doesn't need to call User/Order Service to figure out who to
notify).

## Kafka topics this service produces/consumes
| Topic | Direction | Payload | Consumer group (this service) |
|---|---|---|---|
| `notification.requested` | consumes | `NotificationRequestedEvent` | `notification-service` |

This service produces no topics of its own — it's a pure sink at the end of
the chain. Event classes live in `event/` and are this service's own local
copy (not shared with the producing services). Since this service now
consumes exactly one topic/event type, `spring.json.value.default.type` is
set once at the consumer-factory level in `application.yml`, not
per-listener.

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
