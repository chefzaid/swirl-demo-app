# ADR 0004: Coordinate Services Asynchronously Through Kafka

- Status: Accepted
- Date: 2026-08-28

## Context

Orders need to demonstrate a cross-service dependency on user data. A synchronous HTTP call would be simpler but would not prove event-driven processing, retry/DLT behavior, keyed ordering, eventual state, or independent service availability.

Adding both synchronous and asynchronous coordination for the same minimal flow would obscure the template.

## Decision

Coordinate order validation only through Kafka:

- order creation persists `PENDING`
- keyed `OrderEvent` request goes to `order_topic`
- `swirlapp-user` resolves the user and publishes to `order_result_topic`
- `swirlapp-order` validates and applies the result
- both primary and DLT topics have three partitions
- producers use `acks=all`, idempotence, delivery timeout, and bounded retry
- consumers acknowledge per record, retry transient failures four attempts by default, and publish exhausted records to matching DLTs
- invalid contracts/states are non-retryable
- result publication is awaited before request acknowledgement
- exact duplicate final results are idempotent
- messaging is feature-switched

Do not add a circuit breaker or `@Async` wrapper around this flow. Kafka is already the asynchronous boundary.

## Rationale

The flow demonstrates event ownership, partition keys, business rejection versus technical failure, retry classification, dead letters, and eventual consistency with minimal domain code.

Awaiting the result publish prevents acknowledging the request before its response is durably accepted by the producer path.

State/identity validation makes replay and malformed messages visible instead of silently corrupting an order.

## Consequences

Callers observe a temporary `PENDING` state and may need to refresh/poll.

Three partitions cap active consumption parallelism per group at three.

Producer idempotence does not make the database insert and event publish atomic. A process can fail between them and strand an order. [ADR 0011](0011-transactional-outbox.md) closes that gap with a transactional outbox; a durable inbox remains the next step if restart-safe deduplication is required.

DLT records require inspection/retention/replay operations that are not yet automated.

Event schema compatibility must be defined before independently versioning producers and consumers beyond this demo.
