# Product Service

## Responsibility
Owns the product catalog: name, description, price, and a unique SKU. Source
of truth for `productId` values referenced by Inventory (stock levels) and
Order (line items) — those services store the id only, they do not copy or
own product data (loose coupling per `plan.md`).

## Current phase status
Implemented as of **Phase 1**: CRUD REST API + Postgres persistence only.
No Kafka events yet.

## Tech
- Spring Boot 3.3.4, Spring Data JPA, Bean Validation
- Database: PostgreSQL, schema `productdb` (own database)
- Port: `8082`

## Data model
| Field | Type | Notes |
|---|---|---|
| id | Long | PK, generated |
| name | String | required |
| description | String | optional |
| price | BigDecimal | required, >= 0 |
| sku | String | required, unique |

## Endpoints
Base path: `/api/products` (direct) or via gateway on port `8080`.

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/products` | `ProductRequest` | 201 + `ProductResponse` |
| GET | `/api/products` | – | 200 + `List<ProductResponse>` |
| GET | `/api/products/{id}` | – | 200 + `ProductResponse` / 404 |
| PUT | `/api/products/{id}` | `ProductRequest` | 200 + `ProductResponse` / 404 |
| DELETE | `/api/products/{id}` | – | 204 / 404 |

`ProductRequest`: `{ "name": string, "description": string?, "price": number >= 0, "sku": string }`
`ProductResponse`: `{ "id": number, "name": string, "description": string, "price": number, "sku": string }`

Errors follow the shared `ApiError` shape.
- 404 when product id not found
- 409 when SKU already exists
- 400 with field-level `details` on validation failure

## How other services find this service
No service registry yet (Phase 1 scope). Discovery is static config only:
- Inventory Service stores `productId` as a plain foreign key but does **not**
  call Product Service directly in Phase 1 (no cross-service HTTP calls
  implemented yet — each service currently only talks to its own database).
- Order Service similarly stores `productId`/`price` inside `OrderItem` at
  order-creation time rather than looking the product up live.
- External clients reach this service through the **API Gateway** (port
  `8080`), which routes `Path=/api/products/**` to `http://localhost:8082`.

## Communication style
Synchronous REST/JSON only in Phase 1. No Kafka producer/consumer yet.
