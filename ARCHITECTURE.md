# Architecture Overview

This file is the top-level map of the system. It is meant to be
**regenerated/updated at the end of every phase** in `plan.md`, since the
communication pattern between services (currently plain REST) is expected to
change materially once Kafka is introduced (Phase 5+ per `plan.md`/`project.txt`).

> Last updated: **Phase 2** (Order Service synchronously calls Inventory
> Service and Payment Service via OpenFeign, with Resilience4j retries and a
> circuit breaker on both calls). See each service's own `README.md` for
> full detail — this file only summarizes and cross-links.

## Services

| Service | Port | Database | Responsibility |
|---|---|---|---|
| [user-service](user-service/README.md) | 8081 | `userdb` | User accounts |
| [product-service](product-service/README.md) | 8082 | `productdb` | Product catalog |
| [inventory-service](inventory-service/README.md) | 8083 | `inventorydb` | Stock levels per product |
| [order-service](order-service/README.md) | 8084 | `orderdb` | Orders + order items |
| [payment-service](payment-service/README.md) | 8085 | `paymentdb` | Payment records per order |
| [notification-service](notification-service/README.md) | 8086 | `notificationdb` | Notification records per user |
| [api-gateway](api-gateway/README.md) | 8080 | – | Single entry point, routes to the 6 services above |

Each service owns its own Postgres database (see
`infra/postgres/init-databases.sql`) — no service reads or writes another
service's tables directly. This is the "database-per-service" pattern:
services only reference each other by plain numeric id
(`userId`, `productId`, `orderId`, …), never by foreign key constraint across
databases.

## How services communicate today (Phase 2)

**One real service-to-service flow exists: placing an order.**
`order-service` calls `inventory-service` and `payment-service` synchronously
over HTTP (OpenFeign), wrapped in Resilience4j retry + circuit breaker. Every
other cross-service reference (`userId` in Order, `productId` in Inventory,
`orderId` in Payment) is still just an opaque number — no other service pair
calls each other yet.

**Order placement flow** (`POST /api/orders`, see order-service's README for
full detail):
1. Order is persisted immediately as `CREATED` (needs an id before Payment
   Service can be called — it requires non-null `orderId`).
2. For each line item, Order calls `POST /api/inventory/reserve` on
   Inventory Service to decrement stock. Each successful reservation is
   tracked.
3. If all items reserve successfully, Order calls `POST /api/payments` on
   Payment Service to record a `SUCCESSFUL` payment for the order total.
4. **All succeed** → order becomes `CONFIRMED`.
5. **Any step fails** (insufficient stock, payment rejected, timeout, open
   circuit breaker) → every already-reserved item is released back via
   `POST /api/inventory/release` (compensating action), and the order is
   persisted as `CANCELLED`. The HTTP response is still `201` — the
   `status` field in the body is the signal, not the status code.

This is a plain try/rollback in the calling service, not a formal saga
(that's Phase 5) — appropriate for a single synchronous orchestrator.

Everything else (User, Product, Notification, and all CRUD endpoints on
Order/Inventory/Payment themselves) remains exactly as it was in Phase 1:
loosely coupled, no cross-service calls, duplicated DTOs per the project's
cross-cutting rule.

## How services discover each other

There is **no service registry** (Eureka/Consul) in this project. All
"discovery" today is static configuration:

- **External clients → services**: through the API Gateway
  (`api-gateway`, port `8080`), which has a hardcoded route table mapping
  `/api/{resource}/**` path prefixes to each service's `localhost:<port>`.
  See [api-gateway/README.md](api-gateway/README.md) for the exact table.
- **Service → service**: as of Phase 2, `order-service` finds
  `inventory-service` and `payment-service` via plain hardcoded URLs in its
  own `application.yml` (`services.inventory.url=http://localhost:8083`,
  `services.payment.url=http://localhost:8085`), consumed by
  `@FeignClient(url = "${...}")` declarations. This is the same
  "static config, no registry" pattern as the gateway — just one level
  lower, service-to-service instead of client-to-gateway. When Kafka
  arrives (Phase 3+), this section must be updated to describe which calls
  moved from direct HTTP to event-driven and which (if any) stayed
  synchronous.

## Planned evolution (do not implement yet — tracked here for context only)

Per `plan.md`/`project.txt`, later phases are expected to add:
- Kafka topics/producers/consumers for asynchronous, event-driven
  communication between services (this is the primary learning goal of the
  whole project)
- Spring Security / auth on the gateway and services
- Redis caching
- Testcontainers-based integration tests
- Docker Compose bringing up the services themselves (Phase 11) — today's
  `docker-compose.yml` only runs infra (Postgres, Kafka, Zookeeper, Redis),
  not the Spring Boot apps

When any of the above lands, update this file's "Services" table status line
and the "How services communicate" / "discover" sections above — do not let
this document drift from the code.

## Postman collections

Each service has a `postman_collection.json` in its own directory covering
its CRUD endpoints with realistic request bodies (see each service's
`README.md` for the exact request/response shapes). Import the collection
for the specific service you're testing, or hit the same paths through the
gateway on port `8080` instead of the service's own port.
