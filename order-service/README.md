# Order Service

## Responsibility
Owns orders and their line items. An `Order` has one `userId` (foreign key
reference only, no call to User Service) and a list of `OrderItem` children,
each carrying its own `productId`, `quantity`, and `price` snapshot at order
time (so historical orders aren't affected by later product price changes).
`totalAmount` is computed server-side as the sum of `price * quantity` across
all items.

## Current phase status
**Phase 2** (Synchronous Communication) added: placing an order now
synchronously reserves stock in Inventory Service and charges Payment
Service via OpenFeign, with Resilience4j retries + a circuit breaker on both
calls. No Kafka events yet — that's Phase 3+.

Phase 1 baseline: CRUD REST API + Postgres persistence.

## Tech
- Spring Boot 3.3.4, Spring Data JPA, Bean Validation
- Spring Cloud OpenFeign (declarative HTTP clients) + Resilience4j
  (`spring-cloud-starter-circuitbreaker-resilience4j`)
- Database: PostgreSQL, schema `orderdb` (own database)
- Port: `8084`
- `Order` 1:N `OrderItem` via JPA `@OneToMany(cascade = ALL, orphanRemoval = true)`

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
`OrderResponse`:
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

Errors follow the shared `ApiError` shape.
- 404 when order id not found
- 400 with field-level `details` on validation failure (e.g., empty `items`)

## How order placement actually works now (Phase 2)

`OrderService.create()`:
1. Builds the `Order` + `OrderItem`s and persists it immediately as
   `CREATED` (via `OrderPersister`, its own transaction) so it has an id
   before any downstream call — Payment Service requires a non-null
   `orderId`.
2. For each item, calls `InventoryClient.reserve(productId, quantity)`
   (`POST http://localhost:8083/api/inventory/reserve`) to decrement stock.
   Each successfully reserved item is tracked so it can be rolled back.
3. If all reservations succeed, calls `PaymentClient.charge(orderId, total)`
   (`POST http://localhost:8085/api/payments`) to create a `SUCCESSFUL`
   payment record.
4. If every step succeeds: order status becomes `CONFIRMED`.
5. If any step fails (insufficient stock, payment rejected, timeout, or
   circuit breaker open): every item reserved so far is released via
   `InventoryClient.release(...)` (compensating action, best-effort — a
   release failure is logged but doesn't change the outcome), and the order
   is persisted as `CANCELLED`. The client still gets a `201` with the order
   body — `status: "CANCELLED"` is the signal, not an HTTP error — so check
   the `status` field, not just the response code.

This is deliberately **not** a saga with a dedicated compensation framework
(that's Phase 5) — it's a straightforward try/rollback in the calling
service, appropriate for synchronous orchestration.

## Resilience (Resilience4j)
Both Feign clients (`inventory`, `payment` instance names in
`application.yml`) have:
- **Timeouts**: Feign `connectTimeout: 2000ms`, `readTimeout: 3000ms`
- **Retry**: up to 3 attempts, 300ms wait, retries on `IOException` /
  `feign.RetryableException` (i.e., network/timeout failures — not on 4xx
  business errors like insufficient stock, which fail fast)
- **Circuit breaker**: opens after 50% failure rate over a 10-call sliding
  window, stays open 10s, then allows 3 trial calls (half-open)

Check live state via actuator: `GET /actuator/health` (shows circuit breaker
state per instance) or `GET /actuator/circuitbreakers` /
`GET /actuator/retries`.

## How other services find this service
Still no service registry (Eureka/Consul) — that remains out of scope.
Discovery is static config, now in two places:
- `application.yml`'s `services.inventory.url` / `services.payment.url`
  properties, consumed by the `@FeignClient(url = "${...}")` clients in
  `client/InventoryClient.java` and `client/PaymentClient.java`.
- External clients still reach this service through the **API Gateway**
  (port `8080`), which routes `Path=/api/orders/**` to `http://localhost:8084`.

## Communication style
Synchronous REST/JSON, now including **service-to-service** calls (Order →
Inventory, Order → Payment) via OpenFeign, in addition to client-to-service
calls through the gateway. No Kafka producer/consumer yet — that's Phase 3.
