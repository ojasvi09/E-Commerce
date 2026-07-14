# Notification Service

## Responsibility
Records notifications intended for a user (e.g., order confirmation emails).
Stores `userId` as a plain reference (no call to User Service). Does not
actually send email/SMS in Phase 1 — it only persists the notification
record and its status; real delivery integration is a later-phase concern.

## Current phase status
Implemented as of **Phase 1**: CRUD REST API + Postgres persistence only.
No Kafka consumer yet — in later phases this service is expected to consume
events (e.g., `OrderCreated`) from Kafka and create notifications
automatically instead of via direct REST calls.

## Tech
- Spring Boot 3.3.4, Spring Data JPA, Bean Validation
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

## How other services find this service
No service registry yet (Phase 1 scope). Discovery is static config only:
- No other service calls Notification Service yet in Phase 1. The intended
  design (per `plan.md`) is that Order/User events will be published to
  Kafka in a later phase, and Notification Service will consume them
  asynchronously rather than being called directly over REST.
- External clients reach this service through the **API Gateway** (port
  `8080`), which routes `Path=/api/notifications/**` to `http://localhost:8086`.

## Communication style
Synchronous REST/JSON only in Phase 1. No Kafka producer/consumer yet.
