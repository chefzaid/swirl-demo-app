# ADR 0010: Trace Through Micrometer Observation With The OpenTelemetry Bridge

- Status: Accepted
- Date: 2026-10-14

## Context

An order crosses two services through Kafka. The request ID identifies the HTTP call that created the order, but it stops at the topic, so diagnosing a stuck or rejected order meant matching order IDs and timestamps across two services' logs.

Tracing can be added with the OpenTelemetry Java agent, with the OpenTelemetry SDK used directly, or through Micrometer Observation, which Spring Boot, Spring MVC, and Spring Kafka already use for the metrics the services export.

## Decision

Use Micrometer Tracing with the OpenTelemetry bridge (`micrometer-tracing-bridge-otel`) and the OTLP exporter:

- Spring MVC continues an incoming W3C `traceparent` header or starts a trace
- `spring.kafka.template.observation-enabled` and `spring.kafka.listener.observation-enabled` write the trace context into record headers and continue it in the consumer
- the trace and span IDs are placed in the logging MDC, so JSON logs carry `traceId` and `spanId` next to `requestId`
- spans are exported over OTLP only when `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` is set; otherwise IDs still propagate and appear in logs
- Spring Boot's default sampling probability (10%) applies unless `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` overrides it

The request ID stays: it is returned to callers and is what users can quote.

## Rationale

The services already emit observations for HTTP and Kafka, so the bridge adds spans without new instrumentation code or a JVM agent in the image. OTLP keeps the backend choice (Tempo, Jaeger, a vendor) with the platform. Not using Spring Boot's OpenTelemetry starter avoids its OTLP metrics registry, which would push metrics to a default endpoint beside the existing Prometheus scrape.

## Consequences

Libraries without Micrometer Observation support (for example the JDBC driver) produce no spans; add instrumentation where a slow dependency needs one. Exporting spans needs a collector reachable from each environment; until the platform provides one, traces exist only as IDs in logs.
