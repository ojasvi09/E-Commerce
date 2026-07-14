# Inventory Service

## Responsibility
Tracks stock quantity per product. One inventory row per `productId`
(enforced unique). This service does not own product details (name, price,
etc.) — it only references `productId` as an opaque id, keeping it loosely
coupled from Product Service per `plan.md`'s "duplicate DTOs, don't share
domain models" rule.

## Current phase status
**Phase 2** (Synchronous Communication) added two new endpoints,
`/api/inventory/reserve` and `/api/inventory/release`, so Order Service can
decrement/increment stock synchronously when placing/cancelling an order.
No Kafka events yet (e.g., no `StockReserved`/`StockDepleted` events)
— planned for a later phase.

Phase 1 baseline: CRUD REST API + Postgres persistence.

## Tech
- Spring Boot 3.3.4, Spring Data JPA, Bean Validation
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

## How other services find this service
No service registry yet. Discovery is static config only:
- **Order Service calls this service synchronously** (Phase 2) via an
  OpenFeign client (`InventoryClient` in order-service), pointed at
  `http://localhost:8083` through the `services.inventory.url` property in
  order-service's `application.yml`. This is a direct HTTP call, not
  event-driven — Kafka-based decoupling is planned for Phase 3+.
- External clients reach this service through the **API Gateway** (port
  `8080`), which routes `Path=/api/inventory/**` to `http://localhost:8083`.

## Communication style
Synchronous REST/JSON. As of Phase 2, this includes inbound
**service-to-service** calls from Order Service (reserve/release), in
addition to the gateway-routed CRUD endpoints. No Kafka producer/consumer yet.
