# User Service

## Responsibility
Owns user identity: create, read, update, and delete user accounts. Enforces
unique email addresses. This is the source of truth for `userId` values that
other services (Order, Notification) reference by plain numeric id — no other
service is allowed to write to the `userdb` database.

## Current phase status
Implemented as of **Phase 1**: CRUD REST API + Postgres persistence only.
No authentication/authorization yet (planned for a later phase per `plan.md`),
no Kafka events published yet.

## Tech
- Spring Boot 3.3.4, Spring Data JPA, Bean Validation
- Database: PostgreSQL, schema `userdb` (own database, per database-per-service rule)
- Port: `8081`

## Data model
| Field | Type | Notes |
|---|---|---|
| id | Long | PK, generated |
| name | String | required |
| email | String | required, unique, validated format |
| password | String | required, min 8 chars (stored as-is in Phase 1 — hashing not yet implemented) |

## Endpoints
Base path: `/api/users` (direct) or `/api/users` via gateway on port `8080`.

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/users` | `UserRequest` | 201 + `UserResponse` |
| GET | `/api/users` | – | 200 + `List<UserResponse>` |
| GET | `/api/users/{id}` | – | 200 + `UserResponse` / 404 |
| PUT | `/api/users/{id}` | `UserRequest` | 200 + `UserResponse` / 404 |
| DELETE | `/api/users/{id}` | – | 204 / 404 |

`UserRequest`: `{ "name": string, "email": string, "password": string (min 8) }`
`UserResponse`: `{ "id": number, "name": string, "email": string }` (password never returned)

Errors follow the shared `ApiError` shape: `{ timestamp, status, error, message, details[] }`.
- 404 when user id not found
- 409 when email already exists
- 400 with field-level `details` on validation failure

## How other services find this service
There is **no service registry (e.g., Eureka/Consul) yet** — that is out of
scope for Phase 1. Discovery today is purely static configuration:
- Internally, other services would call `http://localhost:8081` directly if
  they needed user data (none do yet in Phase 1 — Order only stores `userId`
  as an opaque foreign key, it does not call User Service).
- Externally, clients reach this service through the **API Gateway**
  (`api-gateway`, port `8080`), which routes `Path=/api/users/**` to
  `http://localhost:8081` (see `api-gateway/src/main/resources/application.yml`).

## Communication style
Synchronous REST/JSON only in Phase 1. No Kafka producer/consumer yet — event
publishing (e.g., `UserCreated`) is planned for a later phase per `plan.md`.
