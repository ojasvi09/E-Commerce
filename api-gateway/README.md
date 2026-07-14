# API Gateway

## Responsibility
Single entry point for external clients. Routes incoming HTTP requests to
the correct backend service based on path prefix, so clients only need to
know one host/port (`localhost:8080`) instead of six.

## Current phase status
Implemented as of **Phase 1**: Spring Cloud Gateway with static route
definitions. No auth/rate-limiting/circuit-breaking filters yet — those are
later-phase concerns per `plan.md`.

## Tech
- Spring Cloud Gateway (Spring Cloud 2023.0.3)
- Port: `8080`

## Routing table
Defined in `src/main/resources/application.yml`. This is **static
configuration**, not dynamic service discovery — there is no Eureka/Consul
registry in this project yet. Each route hardcodes the backend's
`localhost:<port>`, which only works because every service currently runs
on the same machine with fixed ports.

| Route id | Path predicate | Forwards to |
|---|---|---|
| user-service | `/api/users/**` | `http://localhost:8081` |
| product-service | `/api/products/**` | `http://localhost:8082` |
| inventory-service | `/api/inventory/**` | `http://localhost:8083` |
| order-service | `/api/orders/**` | `http://localhost:8084` |
| payment-service | `/api/payments/**` | `http://localhost:8085` |
| notification-service | `/api/notifications/**` | `http://localhost:8086` |

## Management endpoints
`/actuator/health`, `/actuator/info`, `/actuator/gateway` are exposed
(`management.endpoints.web.exposure.include: health,info,gateway`).

## Why static routing instead of service discovery
Phase 1 scope is deliberately minimal per `plan.md` — a discovery registry
(e.g., Eureka) adds real value once services can move between hosts/ports or
scale to multiple instances, which isn't needed yet at this stage of the
learning project. If/when that need appears in a later phase, this file
should be updated to reflect the change (e.g., `lb://user-service` with a
Eureka client instead of a hardcoded `http://localhost:8081`).
