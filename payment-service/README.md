# Payment Service

## Responsibility
Records payment attempts/results against an order. Stores `orderId` as a
plain reference (no call to Order Service) plus the amount and status of the
payment. Does not process real payments in Phase 1 — status is set directly
by the caller (no payment gateway integration yet).

## Current phase status
**Phase 2** (Synchronous Communication): Order Service now calls
`POST /api/payments` synchronously (via OpenFeign) as part of placing an
order, to record a `SUCCESSFUL` payment for the order total. This service's
own code is unchanged from Phase 1 — it's simply now a real caller instead
of only being reachable through the gateway. No Kafka events yet (e.g., no
`PaymentSucceeded`/`PaymentFailed` events) and no real payment gateway/
provider integration — planned for later phases.

## Tech
- Spring Boot 3.3.4, Spring Data JPA, Bean Validation
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

## How other services find this service
No service registry yet. Discovery is static config only:
- **Order Service calls this service synchronously** (Phase 2) via an
  OpenFeign client (`PaymentClient` in order-service), pointed at
  `http://localhost:8085` through the `services.payment.url` property in
  order-service's `application.yml`.
- Payment Service still does not call back into Order Service to validate
  `orderId` — it trusts the caller. If the payment call itself fails or
  times out, Order Service's Resilience4j retry/circuit breaker handles it
  and cancels the order (see order-service's README) — Payment Service does
  not need to know about that fallback behavior.
- External clients reach this service through the **API Gateway** (port
  `8080`), which routes `Path=/api/payments/**` to `http://localhost:8085`.

## Communication style
Synchronous REST/JSON. As of Phase 2, this includes an inbound
**service-to-service** call from Order Service, in addition to the
gateway-routed CRUD endpoints. No Kafka producer/consumer yet.
