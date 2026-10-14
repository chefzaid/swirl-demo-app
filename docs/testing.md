# Testing Guide

Swirl Demo App verifies the reusable layers it demonstrates: API contracts, validation and errors, transaction-oriented services, caching, security, Kafka state handling, Angular integration, and real browser authentication.

## Test Layers

Backend layers:

- plain unit tests for services, listeners, filters, cache configuration, and health indicators
- Spring MVC slice tests for request validation, status codes, Problem Details, and response DTO privacy
- full Spring Boot + MockMvc security tests for anonymous and JWT-authenticated access
- full-context HTTP tests (`*ApiHttpTest`) that start the service on a random port with its in-memory database and call it over a real connection, through every servlet filter
- Mockito fakes for repositories, Kafka templates, and infrastructure dependencies
- ArchUnit rules for module boundaries, service layering, and coding conventions
- JaCoCo verification during the Maven `verify` phase

Frontend layers:

- Vitest service tests with Angular HTTP testing support
- component tests for login, users, and orders
- auth guard and interceptor tests
- public runtime-configuration validation and startup failures
- notification and error behavior tests
- Playwright smoke journey and WCAG 2.2 AA accessibility checks against the development UI
- Playwright live/full-stack journey through Keycloak and both secured APIs

Deployment layers:

- Kubernetes manifest rendering
- post-rollout backend/frontend HTTP smoke tests
- exact Argo CD revision and health wait
- Chromium, Firefox, and WebKit acceptance against the deployed ingress

Run the [onboarding and release checks](deployment.md#add-or-reconfigure-this-repository)
when changing deployment configuration or publication behavior.

## Quick Commands

Backend reactor, tests, coverage report, and gate:

```bash
mvn clean verify
```

Mutation testing of the service and event-state classes with PIT (slower, so opt-in; fails below an 85 percent mutation score per module, reports in `*/target/pit-reports/`):

```bash
mvn -Pmutation verify
```

Frontend unit tests and production build:

```bash
cd swirlapp-web
npm ci
npm test
npm run test:coverage
npm run build-prod
```

Playwright types, browser install, and local E2E:

```bash
npm run test:e2e:types
npm run test:e2e:install
npm run test:e2e
```

Markdown style and internal links in `README.md`, `TODO.md`, and `docs/` (the build job runs both):

```bash
npx --yes markdownlint-cli2@0.23.3
python3 infra/scripts/check-doc-links.py
```

Rules live in `.markdownlint-cli2.jsonc`. The link check fails on a relative link to a missing file or heading anchor; external URLs are not fetched.

With Mask:

```bash
mask test all
mask coverage all
mask build all
```

## Backend Test Map

### Shared module

- `RequestIdFilterTest`: accepted request IDs, unsafe-value replacement, response header, and MDC behavior
- `RateLimitFilterTest`: allowed requests, response headers, and `429` rejection after the configured limit
- `GlobalExceptionHandlerLoggingTest`: constraint violations log the SQL state, never the rejected values
- `FailOpenCacheErrorHandlerTest`: an unreachable cache turns into logged misses, so cached reads and evicting writes still succeed, and counts each failure
- `KafkaReliabilityConfigTest`: environment-scoped topics and dead-letter topics, per-topic retry and dead-letter counters, and unreadable records dead-lettered as their original bytes
- `MessagingHealthIndicatorTest`: optional messaging degrades, required messaging goes down, and probes reuse the last background check
- `AuditTrailTest`: audit entries name the token subject and resource ID, wait for the transaction to commit, and cover rejected tokens and denied requests
- `HttpApiTestSupport`: shared client and token setup for the HTTP tests, published in the module's test JAR
- `ArchitectureRules`: shared ArchUnit rules, published in the module's test JAR: each module depends only on itself and `swirlapp-common`; controllers, services, and repositories stay layered; domain classes stay independent; no field injection, `java.util.logging`, or standard streams
- `CommonArchitectureTest`: the shared module never depends on a service module
- `OrderEventTest`: new events carry the current schema version, unversioned payloads read as version 1, and only newer major versions are refused
- `EventCompatibilityContract`: published in the test JAR and run by each service as `EventCompatibilityTest` with its own Kafka settings: written payloads carry no Java type header, payloads from older producers or with unknown fields still read, and unreadable payloads reach the error handler instead of failing the poll

### User service

- `UserServiceTest`: bounded reads, not-found behavior, create/edit normalization, duplicate username/email rejection, deletion, persistence, and cache-oriented service behavior
- `OrderListenerTest`: approved result, missing-user rejection, synchronous result publication, result event IDs derived from the request ID, refusal of a newer schema version, and propagation of transient failures
- `NotificationServiceTest`: notification logs carry IDs, never names, usernames, or email addresses
- `UserControllerTest`: create/get/list/edit/delete responses, invalid bodies, invalid IDs/limits, malformed JSON, and private entity-field exclusion
- `CacheConfigTest`: Redis serialization and TTL configuration
- `DatabaseHealthIndicatorTest`: healthy and failed repository access
- `SecurityConfigTest`: anonymous API rejection, JWT-authenticated API access, CORS preflight, and valid Kafka producer settings in a full application context
- `UserArchitectureTest`: the shared `ArchitectureRules` for the user service
- `UserApiHttpTest`: over real HTTP, create/list/delete with duplicate rejection, CORS preflight for allowed and foreign origins, and public probes beside a protected API
- `UserAuthorizationMatrixTest`: over real HTTP with Keycloak-shaped tokens, anonymous, forged, authenticated, wrong-role, and permitted callers each get the expected status, and denied deletes never reach the service
- `SecurityAuditTest`: denied anonymous requests and rejected bearer tokens reach the audit trail without the token
- `MessagingReadinessTest`: a `DEGRADED` messaging contributor keeps readiness at 200, a `DOWN` one turns it to 503, and liveness ignores both
- `LatencyObjectiveMetricsTest` and `TracingTest`: request timers publish the 300 ms objective bucket; incoming `traceparent` headers reach the logging MDC and outgoing Kafka records

### Order service

- `OrderServiceTest`: bounded reads, not-found behavior, create/edit/delete flow, edit revalidation, and the event each change records
- `EventRetentionTest`: published outbox rows and inbox entries older than the retention are purged, and a dry run only counts them
- `SchedulerLockTest`: a scheduler lock has one holder until released, and the retention job skips its run while another replica holds the lock
- `OrderEventOutboxTest` and `OutboxRelayTest`: events are stored per environment topic and keyed by order, nothing is stored without messaging, acknowledged events are marked published, rejected or unreadable ones stay pending, old rows are purged, and the backlog is exported
- `OrderResultListenerTest`: approval/rejection, duplicate results, deleted orders, superseded edit results, invalid status/state, newer schema versions, retryable behavior, and the inbox (an applied result is recorded; a known event ID is skipped and counted)
- `OrderControllerTest`: create/get/list/edit/delete responses and input/path/query error contracts
- `CacheConfigTest`: Redis serialization and TTL configuration
- `DatabaseHealthIndicatorTest`: healthy and failed repository access
- `SecurityConfigTest`: anonymous API rejection, JWT-authenticated API access, CORS preflight, and valid Kafka producer settings in a full application context
- `OrderArchitectureTest`: the shared `ArchitectureRules` for the order service
- `OrderApiHttpTest`: over real HTTP, the bearer-token challenge, the create/read/edit/delete flow with request-ID and rate-limit headers and audit entries, Problem Details for invalid input, and rejected tokens

## Direct Maven Workflows

Run one module and its required reactor dependencies:

```bash
mvn -pl swirlapp-user -am test
mvn -pl swirlapp-order -am test
```

Run one class:

```bash
mvn -pl swirlapp-user -am -Dtest=UserServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl swirlapp-order -am -Dtest=OrderResultListenerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Run one method:

```bash
mvn -pl swirlapp-user -am -Dtest=UserServiceTest#createUserNormalizesInput \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Test reports:

- `swirlapp-common/target/surefire-reports/`
- `swirlapp-user/target/surefire-reports/`
- `swirlapp-order/target/surefire-reports/`
- `swirlapp-user/target/site/jacoco/index.html`
- `swirlapp-order/target/site/jacoco/index.html`

The shared module skips its JaCoCo gate because it is infrastructure code tested directly. Each deployable service applies a 60 percent instruction gate after excluding application bootstrap, configuration, domain, and DTO classes.

## Backend Test Profiles

`application-test.yml` gives each service:

- a separate H2 database in PostgreSQL compatibility mode
- Hibernate `create-drop`
- disabled SQL seed initialization
- a simple cache
- stopped Kafka listeners
- disabled messaging and security by default

Security integration tests explicitly enable `app.security.enabled` and mock `JwtDecoder`. This verifies the real resource-server filter chain without calling Keycloak.

Use production-profile smoke or container tests when behavior depends on PostgreSQL, Flyway, Redis, Kafka, or Keycloak. H2 unit/slice tests do not prove those integrations.

## Frontend Test Map

Current Vitest specs:

- `login.component.spec.ts`
- `user.component.spec.ts`
- `order.component.spec.ts`
- `auth.service.spec.ts`
- `user.service.spec.ts`
- `order.service.spec.ts`
- `notification.service.spec.ts`
- `auth.guard.spec.ts`
- `auth.interceptor.spec.ts`

The current clean frontend run executes 52 tests across 9 files.

Coverage output:

- terminal summary from `npm run test:coverage`
- LCOV output under `swirlapp-web/coverage/`

CI uses `npm run test:ci`, which writes `swirlapp-web/test-results.xml` for GitLab CI and an LCOV report for analysis tooling.

## Playwright Suites

### Local UI smoke

`e2e/home.spec.ts` checks that the login route and SSO call to action render. `e2e/accessibility.spec.ts` runs axe-core WCAG 2.2 AA rules on the login, user, and order screens, with the APIs answered from fixtures, and checks that the primary navigation and the user form are reachable and usable from the keyboard. When `WEB_URL` is unset, Playwright starts the Angular development server automatically.

```bash
cd swirlapp-web
npm run test:e2e
```

### Authenticated integration

`e2e/live-cluster.spec.ts`:

1. loads OIDC discovery
2. starts Authorization Code + PKCE login
3. authenticates through Keycloak
4. loads the secured user directory
5. loads the secured order workflow
6. optionally creates and edits a disposable user
7. creates and edits an order, waiting for Kafka validation after each change
8. deletes the disposable order and user again

Run against an environment:

```bash
cd swirlapp-web
WEB_URL=https://demo.example.com \
OIDC_USERNAME=example-user \
OIDC_PASSWORD='<from Vault/GitLab CI variable>' \
npm run test:integration
```

The test reads the realm from `/runtime-config.json`. Remote targets require both
`OIDC_USERNAME` and `OIDC_PASSWORD`; only `localhost`, `127.0.0.1`, `[::1]` and the
Compose `web` host use disposable demo defaults.

Set `E2E_EXERCISE_WRITES=true` only for disposable environments where creating demonstration records is acceptable.

Browser projects:

- Chromium / Desktop Chrome
- Firefox / Desktop Firefox
- WebKit / Desktop Safari

CI retries failures twice with one worker and forbids committed focused tests.

Artifacts:

- JUnit: `test-results/playwright-junit.xml`
- traces/screenshots/videos: `test-results/playwright/`
- HTML report: `playwright-report/`

Artifacts may contain entered test data. Use only disposable credentials and records.

## Containerized Full-Stack Acceptance

Run the whole production-shaped local system plus Playwright:

```bash
docker compose \
  -f infra/compose/compose.yaml \
  -f infra/compose/compose.test.yaml up \
  --build \
  --abort-on-container-exit \
  --exit-code-from test-runner
```

The test overlay:

- uses an internal Keycloak issuer reachable through the web proxy
- starts the same PostgreSQL, Redis, Kafka, Keycloak, user, order, and web services as `infra/compose/compose.yaml`
- installs exact frontend dependencies in a Playwright image
- enables write exercise
- persists test results and reports in named volumes

Clean it up after inspection:

```bash
docker compose \
  -f infra/compose/compose.yaml \
  -f infra/compose/compose.test.yaml down -v
```

## Production-Profile Smoke Testing

For persistence changes, verify more than H2:

1. build the packaged JARs
2. start a disposable PostgreSQL and Redis
3. start both services under `prod` with security/messaging disabled only for the smoke
4. wait for readiness
5. call both list APIs
6. inspect both Flyway history tables and affected columns
7. remove only the disposable resources created for the test

This catches Flyway auto-configuration, PostgreSQL SQL differences, shared-schema baselining, Redis wiring, and Hibernate validation errors.

Testcontainers should eventually automate this workflow; it remains on the roadmap.

## Kubernetes And Delivery Verification

Render manifests without changing a cluster:

```bash
kubectl kustomize infra/k8s
```

Check the workload policy on the base, the HA overlay and every committed environment (the build job runs it):

```bash
python3 infra/scripts/check-manifest-policy.py
```

Every Deployment, StatefulSet, DaemonSet, Job and CronJob must run as non-root with a `RuntimeDefault` seccomp profile and no service-account token, without host namespaces; every container must block privilege escalation, use a read-only root filesystem, drop all capabilities, request CPU and memory, limit memory, and pin its image; long-running containers also need liveness and readiness probes. Pass Kustomize directories as arguments to check others.

Validate the Prometheus alert rules and replay their unit tests (the build job runs both; see [Service Levels And Alerts](slo.md#loading-and-testing-the-rules)):

```bash
promtool check rules infra/observability/slo-rules.yaml
promtool test rules infra/observability/slo-rules.test.yaml
```

The [delivery flow](deployment.md#delivery-flow) defines CI job dependencies and
release gates. `infra/scripts/test-deployment.py` verifies real Kustomize output
for local, remote and mixed targets, namespace-specific ingress and secret
references, and Git promotion that leaves other environments unchanged. Run it
with the [deployment checks](deployment.md#verification-and-rollback) when
changing deployment configuration.

## Test Design Rules

### Start at the narrowest layer

Use a unit test for service state or filter logic, MVC test for HTTP mapping, full context for filter-chain integration, and Playwright only for critical cross-system journeys.

### Assert contracts, not implementation calls alone

Good outcomes include:

- invalid query limit returns a validation Problem Detail
- anonymous API call returns `401`
- transient user lookup failure is retried rather than converted to rejection
- duplicate result leaves an already-final order unchanged
- response DTO does not reveal entity audit principals or versions

### Cover failure and replay paths

Infrastructure templates need explicit coverage for malformed input, missing resources, duplicate messages, invalid transitions, dependency failure, and retry exhaustion.

### Keep fixtures minimal

Seed only enough user/order state to prove the technical behavior. Large product fixtures obscure template mechanics.

### Prefer accessible frontend selectors

Use roles, labels, headings, and visible text. Avoid selectors coupled to CSS layout when a semantic selector exists.

## Known Verification Gaps

Not yet first-class:

- Testcontainers integration suite
- automated Flyway upgrade tests from every supported schema version
- durable DLT/replay and consumer restart tests
- consumer-driven HTTP/event contracts
- load, soak, chaos, and visual-regression suites
- automated container and secret scanning beyond the current dependency and SonarQube reports

Track these in [TODO.md](../TODO.md), not as existing coverage.

## Troubleshooting

### Mockito warns about dynamic agent loading

Surefire attaches the exact Mockito agent through `argLine`. Run Maven through the root reactor so the configured plugin and resolved dependency are used.

### Security test unexpectedly returns 401

Use Spring Security's JWT request post-processor and ensure the test enables security explicitly. `@WithMockUser` alone does not exercise an OAuth2 resource-server bearer-token flow equivalently.

### Playwright cannot find browser binaries

```bash
cd swirlapp-web
npm run test:e2e:install
```

### Public E2E receives an edge challenge

The public Cloudflare path may challenge headless clients. GitLab CI resolves the Swirl Demo App and canonical Keycloak public hostnames directly to the in-cluster ingress. `IGNORE_HTTPS_ERRORS=true` is used only for that origin route because the ingress has a Cloudflare Origin CA certificate; do not use it for normal public-endpoint validation.

### A browser test passes alone but fails in the suite

All projects execute the same flow. Use unique test data per `testInfo.project.name`, avoid shared ordering assumptions, and inspect retained traces.

## Related Guides

- [Development](./development.md)
- [Features](./features.md)
- [Data Model](./data-model.md)
- [Security](./security.md)
- [Operations](./operations.md)
