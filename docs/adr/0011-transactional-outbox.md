# ADR 0011: Publish Order Events Through A Polling Transactional Outbox

- Status: Accepted
- Date: 2026-10-14

## Context

`swirlapp-order` inserted or changed an order, committed, and then sent the `OrderEvent` from application code. A crash, deployment, or broker outage between the commit and the send left the order `PENDING` with no event and nothing to retry it ([ADR 0004](0004-kafka-event-coordination.md)). Sending before the commit has the opposite failure: an event for an order that never existed.

## Decision

Write each order event into an `order_outbox` table in the same transaction as the order change, and publish it from a separate relay:

- `OrderEventOutbox.enqueue` stores the topic, the record key (the order ID), and the JSON payload. It requires an active transaction, so an event exists exactly when its order change commits.
- `OutboxRelay` polls every second (`app.messaging.outbox.poll-interval`), locks up to 100 unpublished rows (`batch-size`) with `FOR UPDATE SKIP LOCKED`, sends them, waits for Kafka's acknowledgement, and marks them published in the same transaction. Rows another pod holds are skipped, so replicas relay in parallel without sending a row twice at the same time.
- A failed send leaves the row pending with its attempt count raised; the next poll retries it.
- Each record carries a `swirl-event-id` header (`order-outbox-<row id>`), identical on every republish of the row.
- Published rows are deleted after seven days (`app.messaging.event-retention`) by a daily job.
- `swirl_outbox_pending`, `swirl_outbox_published_total`, and `swirl_outbox_failed_total` expose the backlog, and `SwirlOutboxBacklog` alerts when it stays above 100.

Delivery is at least once. A relay that crashes after Kafka acknowledged a batch but before its commit publishes that batch again, and a retried row can follow a newer row for the same order. The user service gives each result the request's ID plus `:result`, and the order service records applied result IDs in an `order_inbox` table in the same transaction as the order update, so a redelivered or republished result is skipped even after a restart and counted in `swirl_events_duplicates_total`.

## Alternatives

**Change data capture.** Debezium (Kafka Connect) reads the PostgreSQL write-ahead log and its outbox event router turns `order_outbox` inserts into Kafka records. Publication latency drops and the application stops polling, but the platform must run Kafka Connect, grant a logical replication slot, and monitor slot lag, because an abandoned slot makes PostgreSQL retain WAL indefinitely. Choose it when the platform already operates Debezium. The table stays the same; only `OutboxRelay` goes away.

**Kafka transactions with the database.** Kafka cannot join a PostgreSQL transaction, so a "best effort" chained transaction still has a window where one commits and the other does not.

**Listen to your own events.** Publishing first and updating the database from the consumer makes the API answer before its own write exists, which the synchronous create and edit endpoints cannot accept.

## Consequences

Order events reach Kafka up to one poll interval after the commit. Every write adds one insert, and the relay adds one locking query per second per pod. The outbox table needs the cleanup job and the backlog alert to stay small. Publishing no longer happens on the request thread, so a Kafka outage never slows an API response.
