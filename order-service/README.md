# Order Service

## Responsibility
Owns orders and their line items. An `Order` has one `userId` (foreign key
reference only, no call to User Service) and a list of `OrderItem` children,
each carrying its own `productId`, `quantity`, and `price` snapshot at order
time (so historical orders aren't affected by later product price changes).
`totalAmount` is computed server-side as the sum of `price * quantity` across
all items.

## Current phase status
**Phase 3** (Kafka Integration) replaced Phase 2's synchronous OpenFeign
calls entirely: placing an order now publishes an event and returns
immediately with status `CREATED`. Inventory/Payment/Notification react to
that event asynchronously via Kafka, and this service listens for their
outcomes to update the order's final status. There is no more direct HTTP
call from Order Service to Inventory or Payment — see "How order placement
works now" below.

Phase 1 baseline: CRUD REST API + Postgres persistence.
Phase 2 (superseded): synchronous OpenFeign + Resilience4j — removed in
Phase 3, kept here in history only as context for why some patterns exist
(e.g. `OrderStatus.CREATED` as a real, now much more visible, intermediate
state).

## Tech
- Spring Boot 3.3.4, Spring Data JPA, Bean Validation
- Spring Kafka (`spring-kafka`) — producer for `order.created`, consumer for
  `inventory.failed` / `payment.successful` / `payment.failed`
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
`OrderResponse` — **note the status is now always `CREATED` immediately
after `POST`**, not `CONFIRMED`/`CANCELLED` like Phase 2:
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
Poll `GET /api/orders/{id}` afterward to observe it transition to
`CONFIRMED` or `CANCELLED` once the async chain finishes (typically well
under a second locally, but there is no guaranteed latency — that's the
nature of eventual consistency).

Errors follow the shared `ApiError` shape.
- 404 when order id not found
- 400 with field-level `details` on validation failure (e.g., empty `items`)

## How order placement actually works now (Phase 3)

`OrderService.create()`:
1. Builds the `Order` + `OrderItem`s and persists it as `CREATED`.
2. Publishes an `OrderCreatedEvent` (orderId, userId, totalAmount, items) to
   the `order.created` Kafka topic, keyed by `orderId` (so all events for
   one order land on the same partition and are processed in order).
3. Returns the `CREATED` order immediately — this method no longer waits
   for inventory/payment at all.

Downstream, asynchronously:
4. **Inventory Service** consumes `order.created`, tries to reserve stock
   for every line item (all-or-nothing — see its own README), and publishes
   either `inventory.reserved` or `inventory.failed`.
5. **Payment Service** consumes `inventory.reserved`, charges the order
   total, and publishes either `payment.successful` or `payment.failed`.
6. **This service** listens for all three possible outcomes
   (`inventory.failed`, `payment.successful`, `payment.failed`) via
   `event/OrderEventListener.java` and updates the order:
   - `inventory.failed` or `payment.failed` → `OrderService.markCancelled()`
   - `payment.successful` → `OrderService.markConfirmed()`
7. **Notification Service** independently consumes the same three outcome
   topics and creates a notification record for the user — it does not wait
   for or depend on Order Service's own listener.

This is deliberately **not yet a formal saga** with defined compensating
transactions per step (that's Phase 5) — Phase 3's scope is just "convert
the chain to producer/consumer," which is what this is: a straight-line
async pipeline, not a saga with a coordinator or compensation graph.

## Kafka topics this service produces/consumes
| Topic | Direction | Payload | Consumer group (this service) |
|---|---|---|---|
| `order.created` | produces | `OrderCreatedEvent` | – (n/a, this service produces it) |
| `inventory.failed` | consumes | `InventoryFailedEvent` | `order-service` |
| `payment.successful` | consumes | `PaymentSuccessfulEvent` | `order-service` |
| `payment.failed` | consumes | `PaymentFailedEvent` | `order-service` |

All event classes live in `event/` and are duplicated per service (not
shared) — see [[ARCHITECTURE.md]]'s note on why, same loose-coupling rule as
the DTOs in earlier phases. JSON deserialization is configured with
`spring.json.use.type.headers: false` and a per-listener
`spring.json.value.default.type` override, so each service deserializes
into its own local copy of the event class regardless of which package the
producing service used.

## How other services find this service
Still no service registry (Eureka/Consul) — that remains out of scope.
Discovery is now entirely via Kafka topic names (plain string constants in
each service's own `event/KafkaTopics.java`) plus the shared broker address
(`spring.kafka.bootstrap-servers: localhost:9092` in every service's
`application.yml`) — there is no longer any hardcoded service-to-service
URL anywhere in this service (Phase 2's `services.inventory.url` /
`services.payment.url` properties are gone).
- External clients still reach this service through the **API Gateway**
  (port `8080`), which routes `Path=/api/orders/**` to `http://localhost:8084`.
  The gateway itself is unaffected by this phase — it's still synchronous
  REST for client-facing traffic.

## Communication style
- **Client → this service**: synchronous REST/JSON, via the gateway or
  directly on port `8084`, unchanged from Phase 1/2.
- **This service ↔ other services**: fully asynchronous, event-driven via
  Kafka. No more direct HTTP calls to Inventory or Payment Service.
