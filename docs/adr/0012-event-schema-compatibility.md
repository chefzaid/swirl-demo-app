# ADR 0012: Version Order Events By Major Schema Version And Evolve Them Additively

- Status: Accepted
- Date: 2026-10-14

## Context

Both services deploy independently and roll pods one at a time, so for a while an old producer and a new consumer (or the reverse) share a topic. The outbox also stores serialized events, so a pod can relay a payload written by the previous release. Until now the `OrderEvent` contract had no rules for change, and every record carried a `__TypeId__` header naming the Java class, so moving or renaming the record would have broken every consumer.

## Decision

`OrderEvent` carries `schemaVersion`, a major version that starts at 1. Changes follow these rules:

| Change | Allowed within a version | How to ship it |
| --- | --- | --- |
| Add an optional field | yes | Deploy in any order; readers ignore fields they do not know, and a missing field reads as null |
| Add a value to `OrderStatus` | only consumers first | Deploy every consumer that can read the value before any producer sends it |
| Remove, rename, or retype a field, or make one required | no | Raise `schemaVersion`, deploy consumers that read both versions, then switch producers |
| Rename or move the Java record | yes | The payload carries no Java type; consumers read the topic's default type |

- A consumer refuses a major version newer than `OrderEvent.SCHEMA_VERSION` with a non-retryable error, so the record goes to the dead-letter topic and can be replayed after the consumer is upgraded instead of being misread.
- A payload without `schemaVersion`, from a producer or outbox row older than the field, is version 1.
- Producers set `spring.json.add.type.headers: false` and consumers `spring.json.use.type.headers: false`, so the JSON payload alone is the contract.
- `EventCompatibilityContract` runs in each service with its own Kafka settings: it reads an old payload with a foreign type header, reads a payload with unknown fields, and checks the written payload carries no type header.

## Alternatives

**A schema registry with Avro or Protobuf.** It enforces compatibility at publish time and shrinks payloads, but adds a registry service, serializer dependencies, and generated classes to a demonstration with one event type. Adopt it when several teams publish to shared topics.

**A version per topic (`order_topic.v2`).** It isolates breaking changes completely but doubles topics, ACLs, and consumer groups for each change. It stays the fallback for a breaking change too large for a dual-reading consumer.

## Consequences

Additive changes need no coordination. A breaking change takes two deployments and a consumer that reads both versions for the duration of the migration. Consumers must tolerate unknown fields, which Spring Kafka's JSON deserializer already does.
