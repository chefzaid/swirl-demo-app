# Features

This guide catalogs capabilities already represented in Swirl Demo App. Planned work belongs in [TODO.md](../TODO.md), so this file distinguishes implemented behavior from future intent.

Swirl Demo App is a technical template. Its user directory and order flow are intentionally small: they exist to exercise reusable full-stack and distributed-system patterns, not to grow into a product domain. It is for Java and Angular teams whose services must pass security, architecture and supplier reviews ([ADR 0014](adr/0014-template-scope-and-audience.md)).

## Minimal Demonstration Flow

The application has only two business concepts:

1. `swirlapp-user` creates, retrieves, edits, and deletes users.
2. `swirlapp-order` creates a `PENDING` order and publishes an event.
3. `swirlapp-user` resolves the referenced user and publishes an `APPROVED` or `REJECTED` result.
4. `swirlapp-order` validates the result and updates the order.
5. The Angular UI shows both resources and can create, edit, or delete each one.

This flow proves HTTP APIs, persistence, caching, asynchronous communication, authentication, UI integration, tests, observability, and delivery without requiring a large functional model.

## Backend Architecture

Implemented backend structure:

- Maven reactor with `swirlapp-common`, `swirlapp-user`, and `swirlapp-order`
- independent Spring Boot processes and deployable images for the two services
- conventional controller, service, repository, domain, DTO, and configuration layers
- shared library for entity auditing, status/event contracts, error handling, request IDs, rate limiting, and Kafka reliability
- Java 25 virtual threads
- graceful Spring shutdown
- profile-specific local and production behavior
- package and compiler configuration that retains method parameter names for validation errors

The services do not call one another synchronously. Kafka is the service boundary for the order-validation workflow.

## REST API And Contracts

Implemented API behavior:

- `GET /api/users` and `GET /api/orders` with a bounded `limit` query parameter (`1..100`, default `100`)
- `GET /api/users/{id}` and `GET /api/orders/{id}` with positive identifier validation
- `POST /api/users` and `POST /api/orders` with Jakarta Bean Validation
- `PUT /api/users/{id}` and `PUT /api/orders/{id}` for full resource edits
- `DELETE /api/users/{id}` and `DELETE /api/orders/{id}` with `204 No Content` responses
- `201 Created` responses and `Location` headers for created resources
- explicit request and response DTOs instead of serializing JPA entities
- response DTOs that hide audit principals and optimistic-lock versions
- username and email normalization before persistence
- fail-fast username and email conflict checks backed by database uniqueness constraints
- conflict checks that exclude the user currently being edited
- RFC 9457-style Spring `ProblemDetail` responses for validation, malformed input, missing resources, conflicts, unsupported methods/media types, and unexpected failures
- structured field and parameter violation maps
- generated or accepted safe `X-Request-Id` values on every response
- request IDs in error bodies and production logging context
- configurable per-principal or per-IP fixed-window rate limiting
- `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset`, and `Retry-After` response headers
- a 64 KB request-body limit answered with `413` Problem Details, enforced while chunked bodies are read; chunked form and multipart bodies get `411`
- SpringDoc OpenAPI documents for each service and one aggregated Swagger UI; each document names its service, describes its step in the order-validation flow, and carries the build's version, the GPL 3.0 license and a link to this page, and `/actuator/info` reports the same version
- explicit CORS origins, methods, request headers, and exposed response headers
- Keycloak realm roles mapped to `ROLE_*` authorities, and a [`@PreAuthorize` rule](security.md#backend-authorization) that can require a role to delete users
- HTTP compression and forwarded-header support

The APIs demonstrate Richardson Maturity Model level 2 resource and HTTP semantics. A single order can also be read as HAL-FORMS, with links and edit/delete affordances, as an opt-in [hypermedia example](architecture.md#hypermedia-example); the rest of the API stays at level 2.

## Transactions, Persistence, And Auditing

Implemented data behavior:

- Spring Data JPA repositories
- `@Transactional(readOnly = true)` query boundaries
- `@Transactional` write and event-result boundaries
- PostgreSQL 18 for production-shaped environments
- Flyway migrations in production and UAT
- separate Flyway history tables for both services when sharing one PostgreSQL schema
- Hibernate schema validation instead of production schema mutation
- H2 databases, schema SQL, and deterministic seed data for the dependency-free development profile
- optimistic locking through a shared JPA `@Version` field
- JPA auditing with created/modified principals and timestamps
- [audit trail](security.md#audit-trail) entries for user and order changes, rejected tokens, and denied requests
- authenticated principal resolution with a `system` fallback for non-authenticated work
- sorted, bounded collection queries
- custom database health indicators

See [Data Model](./data-model.md) for columns, ownership, and migration rules.

## Caching

Implemented caching behavior:

- Spring Cache annotations on single-resource reads
- cache eviction after creates, edits, deletes, and event-driven order updates
- simple in-memory caches in the default development profile
- Redis-backed caches in UAT and production
- JSON value serialization and string keys
- ten-minute Redis TTLs
- separate `users` and `orders` cache regions
- Redis health contribution in production
- [fail-open cache errors](architecture.md#cache-behavior): an unreachable Redis, a timeout, or an unreadable entry becomes a logged miss served from the database, and Redis is not a readiness dependency
- per-pod stampede protection through `@Cacheable(sync = true)`
- versioned cache keys (`CacheConfig.CACHE_VERSION`), bumped when a cached entity changes shape so pods of the old and new release never read each other's entries

The cache is an acceleration layer, not a source of truth: a Redis outage costs latency, not availability, and concurrent misses for one key load once per pod rather than once across replicas.

## Event-Driven Processing

Implemented Kafka behavior:

- immutable `OrderEvent` records shared by producer and consumers, with a [schema version and compatibility rules](adr/0012-event-schema-compatibility.md)
- keyed order and result records so events for one order retain partition ordering
- three-partition order and result topics
- matching dead-letter topics
- declarative topic creation when messaging is enabled
- feature switch through `app.messaging.enabled` / `KAFKA_ENABLED`
- [readiness semantics](operations.md#health-and-readiness) that keep pods serving while optional messaging is down (`DEGRADED`) or take them out of service when `APP_MESSAGING_REQUIRED=true`; API requests never wait on Kafka because order events go through the outbox
- producers configured with `acks=all`, idempotence, bounded delivery time, and retries
- producer request and delivery timeouts validated together against Kafka client defaults
- record-level listener acknowledgement
- four consumer attempts by default with fixed retry delay
- dead-letter publishing after retry exhaustion
- non-retryable classification for invalid event contracts or state
- synchronous result publication before the source record is acknowledged
- rejection only for the business case where a referenced user does not exist
- propagation of transient processing failures for retry instead of turning them into false business rejections
- order-result identity and status validation
- idempotent handling of duplicate final results, backed by an [inbox table](data-model.md#transactions-and-consistency) keyed by event ID so it survives restarts
- edited orders return to `PENDING` and publish a fresh validation request
- [transactional outbox](adr/0011-transactional-outbox.md): order validation requests are recorded with the order change and published by a polling relay that retries until Kafka acknowledges them, with backlog metrics and alerting; change data capture is a [documented alternative](adr/0011-transactional-outbox.md#alternatives)
- [dead-letter inspection and replay](operations.md#inspect-and-replay-dead-letters) through `/actuator/deadletters`, with 14-day dead-letter retention, an operator role, and audit entries
- a scheduled [retention job](data-model.md#transactions-and-consistency) that purges old outbox and inbox rows on one replica at a time (ShedLock), deletes by age so repeated runs are harmless, and supports a dry run
- late results for superseded edits or deleted orders are ignored safely
- rejection of invalid order state transitions
- local single-node Kafka 4.3 in KRaft mode

Order changes and their events commit together; Kafka delivery is at least once, and the order service skips duplicates by event ID through its inbox, falling back to state checks for records without one.

## Authentication And Security

Implemented security behavior:

- Keycloak realm configuration for the disposable demo
- OAuth 2.0 / OpenID Connect Authorization Code flow with PKCE (`S256`)
- public Angular client without a browser-held client secret
- disabled Resource Owner Password Credentials flow
- access tokens attached by an Angular HTTP interceptor
- protected Angular routes with authentication readiness handling
- Spring Security OAuth2 resource servers validating JWTs
- stateless backend session policy
- all application APIs authenticated in UAT and production
- health, metrics, and API documentation allowlists
- an `APP_API_DOCS_ENABLED` [production switch](security.md#backend-authorization) for Swagger UI and the OpenAPI documents: on in development and Compose, off by default in the `uat` and `prod` profiles, and set per deployed environment by the `apiDocs` setting
- authentication disabled in the default local profile for a fast developer loop
- production H2 console disabled and not security-allowlisted
- CSRF disabled for stateless bearer-token APIs
- narrow CORS configuration
- HTTPS redirect and TLS termination at Traefik Ingress
- [opt-in per-visitor throttling at Traefik](security.md#edge-throttling), set per environment; bot challenges stay with the Cloudflare zone ([Bot Protection](security.md#bot-protection))
- default Spring security headers, with frame embedding relaxed only for the local H2 console
- public ingress removed for Actuator; Prometheus scrapes cluster services through NetworkPolicy
- Kubernetes containers run as non-root with a read-only root filesystem, dropped capabilities, and runtime-default seccomp
- disabled service-account token mounting for application pods
- database and registry credentials supplied through Vault, External Secrets, and Kubernetes secrets

Passwords do not enter the application databases. Keycloak owns password hashing and credential policy. See [Security](./security.md) for trust boundaries and known limitations.

## Angular Application

Implemented frontend behavior:

- Angular 22 standalone application
- lazy-loaded login, users, and orders routes, each with its own tab title
- functional route guard and HTTP interceptor
- OAuth discovery, login, logout, token refresh setup, and auth-state observables
- RxJS service layer for API calls and notifications
- typed user and order models
- create, list, edit, and confirmed-delete views for the two demonstration resources
- a note on each order card explaining its `PENDING`, `APPROVED` or `REJECTED` status
- visible API error handling and notification surface
- `OnPush` change detection
- semantic navigation and form labels used by browser tests, with each visible label part of its control's accessible name
- a login-page sentence naming the app as the reference flow of a service template, linked to the README
- development proxy for both APIs and Keycloak
- runtime public configuration shared across `int`, `uat` and `prod` image deployments
- production build served by unprivileged NGINX
- SPA fallback, compression, immutable static-asset caching, and basic browser security headers

The UI deliberately stays small. It is a vehicle for authentication, API integration, test, build, and deployment patterns.

## Observability

Implemented observability behavior:

- Spring Boot Actuator health, info, metrics, and Prometheus endpoints
- liveness, readiness, and startup probe groups
- custom database health indicators with record counts
- Micrometer Prometheus registry
- [application metrics](operations.md#application-metrics) for Kafka publish and consume latency, consumer lag, retries, DLT records, cache hits and errors, and rate-limit rejections
- pod scrape annotations and NetworkPolicy access for Prometheus
- Grafana dashboard definition for target health, request rate, 5xx rate, response time, JVM heap, CPU, database connections, pod memory, restarts, API 5xx and slow-request ratios against their objectives, consumer lag, event retries and dead letters, cache hit ratio and errors, and rate-limit rejections
- readable development logs
- structured JSON stdout logs in UAT and production
- application name, level, logger, thread, message, arguments, MDC, and stack trace fields
- request-ID correlation through MDC
- a [log redaction policy](security.md#log-redaction-policy) for tokens, personal data, request bodies, and exception details, pinned by log-capture tests
- post-deployment backend and frontend smoke checks

Remote application clusters require separate collector and central dashboard
integration; see [operational observability scope](operations.md#logs-and-request-correlation).
[Service-level objectives](slo.md) for availability, latency, event backlog, and saturation come with Prometheus burn-rate alerts, `promtool` unit tests, and matching dashboard panels.
OpenTelemetry trace context crosses HTTP and Kafka, so `traceId` and `spanId` join `requestId` in every log line, and spans export over OTLP when an endpoint is configured ([ADR 0010](adr/0010-opentelemetry-tracing.md)).

## Testing And Quality Reporting

Implemented verification layers:

- JUnit, Mockito, Spring Boot Test, Spring MVC Test, and Spring Security Test
- focused controller, service, cache, filter, health-indicator, listener, and security tests
- [ArchUnit rules](testing.md#shared-module) for module boundaries (each service depends only on itself and `swirlapp-common`), service layering, and coding conventions
- anonymous-versus-JWT integration tests using full Spring Security contexts
- an [authorization matrix](testing.md#user-service) over real HTTP for anonymous, forged-token, authenticated, wrong-role, and permitted callers
- [full-context HTTP tests](testing.md#test-layers) that start each service on a random port and call it over a real connection through every servlet filter
- event success, duplicate, invalid-state, missing-resource, retryable-failure, and rejection scenarios
- JaCoCo reports and a non-blocking 85 percent combined line-coverage policy; Maven and Vitest also fail below 85 percent per layer
- opt-in [PIT mutation testing](testing.md#quick-commands) of service and event-state classes (`mvn -Pmutation verify`), failing below an 85 percent mutation score per module
- Vitest component, service, guard, interceptor, and authentication tests
- frontend V8 coverage and JUnit-compatible CI output
- Playwright browser tests for Chromium, Firefox, and WebKit
- mocked local UI journeys and live Keycloak/API acceptance
- [axe-core WCAG 2.2 AA checks](testing.md#local-ui-smoke) of the login, user, and order screens, and keyboard checks for the primary navigation and user form
- visual regression screenshots of the login, user, and order screens in all three browsers
- containerized full-stack acceptance through `infra/compose/compose.test.yaml`
- production Angular build and Playwright TypeScript checks in GitLab CI
- [Markdown linting and internal-link validation](testing.md#quick-commands) for `README.md`, `TODO.md`, and `docs/` in the build job
- build-job checks that the Playwright images match the locked Playwright release, that the dev container and Compose pin the same service images, and that every copy of the [identity sentence](code-quality.md#replace-the-identity) matches the README
- immutable JAR archiving after successful builds

See [Testing](./testing.md) for commands, test maps, and where each layer should be used.

## Local Development And Packaging

Implemented developer and packaging support:

- dependency-free default backend profile using H2, simple cache, disabled Kafka listeners, and disabled authentication
- complete Docker Compose environment with application images and all runtime dependencies
- Compose health checks and dependency ordering
- VS Code dev container with Java 25, Maven 3.9, Node 24, npm 12, and Angular CLI 22
- optional dev-container infrastructure profile
- Mask task commands for install, test, coverage, build, and component startup
- multi-stage source-build Dockerfiles
- runtime Dockerfiles that package already-verified CI artifacts
- fixed non-root container users and minimal runtime images
- full-stack Playwright test runner image

## Kubernetes And Delivery

Implemented platform features:

- Kustomize-managed user, order, web, ingress, secret, network, and observability resources
- optional [HA overlay](deployment.md#future-multi-node-ha-profile) with CPU-based HorizontalPodAutoscalers (2 to 4 replicas), PodDisruptionBudgets, hostname topology spread, and surge-only rolling updates, selected per environment by `highAvailability`
- optional [canary overlay](deployment.md#optional-canary-example) that wraps the Deployments in Argo Rollouts with a Prometheus 5xx-ratio analysis aborting a failing user or order canary; it needs the Argo Rollouts controller, which the platform does not install
- ClusterIP services and path-based Traefik Ingress routing
- TLS-only public ingress
- startup, liveness, and readiness probes
- requests and limits for application and CI containers
- restrictive application pod security contexts
- a [manifest policy check](testing.md#kubernetes-and-delivery-verification) in the build job that renders the base, both overlays, and every environment and fails on a workload without the non-root security context, resource requests, probes, a pinned image or, where the target ships NetworkPolicies, an egress policy
- ingress NetworkPolicy for application pods, NGINX, Prometheus, and GitLab CI smoke tests
- egress NetworkPolicy limiting application pods to DNS, the shared datastores, the identity provider and public HTTPS
- Vault-backed database and CI credentials through External Secrets
- persistent Maven/npm dependency caches and private GitLab image registry integration
- explicit GitLab jobs for build, optional tests, Docker validation, optional E2E/quality reports, release, deploy, and set-major-version
- persistent Maven, npm, and Sonar analyzer caches plus 30-day registry-backed Kaniko layer caches
- [backend/frontend Sonar analysis](code-quality.md), manual scans, namespace discovery and a reusable scan-only onboarding contract
- report-only [Trivy scans](security.md#secrets-and-supply-chain) of dependencies, infrastructure code, and secrets in `03-security`, with a CycloneDX source SBOM, and [dependency overrides](security.md#runtime-dependency-maintenance) for the CVEs they report
- daemonless Kaniko image publication with immutable semantic-version tags
- seven-day JUnit, Cobertura, Playwright, and verified-build artifacts plus immutable JAR/SPA archives in GitLab's Generic Package Registry
- GitLab Releases, production Environment deployment history, enterprise project metadata, labels, templates, protected branches, and quality badges
- semantic release/GitOps commit plus an automatically prepared next-minor version
- Argo CD refresh, exact-revision wait, self-healing, and pruning
- post-rollout service smoke tests and real browser acceptance
- optional Ansible Kustomize apply helper and one-time CI/CD configuration script
- push-triggered, bidirectional GitHub/GitLab reconciliation for branches and tags without force pushing, except GitHub-owned Dependabot branches, which GitLab mirrors
- weekly [grouped Dependabot upgrades](development.md#toolchain) for Maven, npm, Dockerfile, and Compose dependencies (minor and patch per ecosystem, Angular packages together, majors one at a time), each verified by the GitLab pipeline on its mirrored branch

GitLab is the CI/CD source of truth; GitHub is the public mirror.

## Current Technical Boundaries

Do not present these as implemented:

- distributed rate limiting (edge throttling counts per Traefik pod)
- ownership rules or business authorization policies beyond the user-delete and dead-letter operator roles
- application-level field encryption or KMS integration
- synchronous HTTP resilience patterns such as circuit breakers or bulkheads
- general-purpose `@Async` jobs
- service mesh, saga, or API gateway owned by this repository
- Testcontainers, consumer-driven contract tests, load tests, or fault-injection tests
- canary delivery in a deployed environment (the canary overlay needs Argo Rollouts, which the platform does not install)
- per-environment replica counts and resource sizing beyond the `highAvailability` switch
- image SBOMs, image signing and verification before deployment, SAST, or DAST (`03-security` already scans with Trivy and writes a source SBOM)

These are tracked as technically focused candidates in [TODO.md](../TODO.md).

## Related Guides

- [Architecture and ADRs](./architecture.md)
- [Development](./development.md)
- [Testing](./testing.md)
- [Deployment](./deployment.md)
- [Operations](./operations.md)
- [Security](./security.md)
