# Technical Roadmap

Swirl Demo App is a reusable application template, not a product roadmap. New work should demonstrate a technical capability that can be transplanted into another service with little domain knowledge. User and order behavior should remain the smallest useful example needed to prove the pattern.

## Contribution Rules For Roadmap Work

- Prefer infrastructure, architecture, security, operability, testing, and developer-experience capabilities over business features.
- Add product behavior only when a technical pattern needs an end-to-end example.
- Keep optional infrastructure behind profiles, properties, or overlays so the default developer loop stays lightweight.
- Every added pattern needs tests, configuration documentation, operational notes, and a clear removal path.
- Do not add distributed-systems patterns merely to name them. Add them when the example can demonstrate the failure mode they solve.

## Priority: Delivery And Data Correctness

- [ ] Replace the order database/Kafka dual write with a transactional outbox and a small relay implementation; provide polling and CDC variants as documented alternatives.
- [ ] Add an inbox or processed-event store so consumer idempotency survives restarts and duplicate events can be measured.
- [ ] Add DLT inspection, replay, quarantine, and retention tooling with an operator-safe runbook.
- [ ] Define event schema compatibility rules and demonstrate versioned events with backward-compatible consumers.
- [ ] Add Testcontainers integration tests for PostgreSQL, Redis, and Kafka, including migration, retry, DLT, duplicate-delivery, and restart scenarios.
- [ ] Demonstrate saga orchestration or choreography only after adding a second compensating write; keep the example domain minimal.

## Priority: Security And API Governance

- [ ] Add reusable role/authority mapping from Keycloak claims and method-level authorization examples with `@PreAuthorize`.
- [ ] Add an authorization test matrix covering anonymous, authenticated, wrong-role, and permitted callers.
- [ ] Move OpenAPI access behind a production switch or operator authorization while leaving it convenient in development.
- [ ] Add a distributed rate-limit implementation at the gateway or Redis layer; retain the in-process filter as the single-instance reference.
- [ ] Add API versioning and compatibility policy, generated frontend clients, and contract-drift checks in CI.
- [ ] Add an optional HATEOAS affordance example and explain when Richardson level 3 is worth the client coupling; keep OpenAPI documentation distinct from hypermedia controls.
- [ ] Add consumer-driven contract tests for service and event contracts.
- [ ] Add a small field-encryption example backed by envelope encryption/KMS abstractions; document key rotation and keep passwords in Keycloak.
- [ ] Add SBOM generation, image signing and verification, secret scanning, SAST, dependency scanning, and a baseline DAST stage.
- [ ] Resolve the findings of the CI `03-security` job (2026-10-04, pipeline 723): one critical prototype-pollution issue in a build-time worker-pool dependency and high denial-of-service issues in the router and HTTP-client dependencies; the job is report-only, so they do not block delivery.
- [ ] Give the order, user and web deployments an explicit non-root pod and container `securityContext` (Trivy KSV-0118 reports the default context, which allows root).
- [ ] Define retention, export, anonymization, and deletion examples for personal data without expanding the demo into an account-management product.
- [ ] Add edge security examples for global throttling, request-size limits, and optional bot protection.

## Priority: Resilience And Asynchronous Work

- [ ] Add a deliberately small outbound HTTP adapter so timeout, retry with jitter, circuit breaker, bulkhead, and fallback behavior can be demonstrated and tested realistically.
- [ ] Provide a bounded `@Async` executor example with context propagation, backpressure, shutdown behavior, and a job-status resource; do not use unbounded fire-and-forget work.
- [ ] Add a scheduled maintenance example for records with explicit retention semantics, leader election, idempotency, and dry-run support.
- [ ] Define cache behavior for Redis outages, stampede prevention, eviction/versioning, and stale-data tradeoffs.
- [ ] Add graceful Kafka degradation and readiness semantics that distinguish optional messaging from a required dependency.
- [ ] Add a dynamic feature-flag SPI with local configuration and an optional OpenFeature/FF4J provider, safe defaults, targeting tests, change auditing, and a clear distinction from user entitlements.
- [ ] Add fault-injection and recovery tests for database, Kafka, Redis, and identity-provider outages.

## Priority: Observability And Operations

- [ ] Add OpenTelemetry traces and propagate trace context across HTTP and Kafka alongside the existing request ID.
- [ ] Add reusable Micrometer business/event metrics for publish latency, consumer lag, retries, DLT records, cache behavior, and rate-limit rejections.
- [ ] Define service-level indicators, objectives, Prometheus alerts, and Grafana alert views for availability, latency, errors, saturation, and event backlog.
- [ ] Add log redaction tests and a documented policy for tokens, personal data, request bodies, and exception details.
- [ ] Add optional log/metric anomaly detection only after stable baselines and actionable alert ownership are defined.
- [ ] Add audit events for security-sensitive actions and document separation between entity auditing, application audit trails, and platform logs.
- [ ] Add backup/restore drills for PostgreSQL and persistent Kafka/Redis data, with recovery-point and recovery-time targets.
- [ ] Add load, soak, and capacity baselines with reproducible k6 or Gatling scenarios.

## Priority: Kubernetes And Delivery

- [ ] Add environment-specific Kustomize overlays or a Helm chart for names, domains, registry, replicas, and resource sizing.
- [ ] Add an optional API-gateway profile for centralized routing, authentication policy, quotas, contract publication, and edge observability without replacing service-side controls.
- [ ] Add HorizontalPodAutoscaler, PodDisruptionBudget, topology spread, and rolling/canary deployment examples.
- [ ] Add explicit egress NetworkPolicies for PostgreSQL, Redis, Kafka, Keycloak, DNS, and approved external endpoints.
- [ ] Add optional service-mesh documentation only when mTLS, traffic policy, or service-to-service telemetry is demonstrated.
- [ ] Add ephemeral preview environments and automated teardown for pull requests.
- [ ] Add policy-as-code checks for Kubernetes manifests and container security contexts.
- [ ] Add release promotion, rollback, provenance, and environment-approval examples without weakening Argo CD ownership.
- [ ] Fix the `swirl-demo-app-int`/`-uat`/`-prod` Argo CD `ComparisonError: Object 'Kind' is missing`: the Application source directory also contains `infra/environments/<env>/settings.json`, which Argo CD parses as a manifest. Exclude it (`directory.exclude`) or move the settings outside the source path.

## Priority: Testing And Quality

- [ ] Add ArchUnit rules for shared-module and service boundaries.
- [ ] Add mutation testing for service and event-state logic.
- [ ] Add REST-assured or full-context HTTP integration tests against packaged applications.
- [ ] Add accessibility testing for the Angular critical path and automated checks for keyboard navigation and semantic labels.
- [ ] Add visual regression tests for the deliberately small UI surface.
- [ ] Extend the existing automatic Sonar reporting with Java 25-specific analyzer validation, OWASP Dependency-Check, and opt-in enforcement of quality gates.
- [ ] Add Markdown linting and internal-link validation for `README.md`, `TODO.md`, and `docs/`.

## Priority: Template Experience

- [ ] Make the dev container application-only and point each development environment at shared infrastructure namespaces; retain local infrastructure as an explicit opt-in profile.
- [ ] Add a template initialization script for group ID, package, service names, image registry, domains, namespaces, and identity-provider settings.
- [ ] Separate example-domain modules from reusable starters so adopters can remove users/orders without untangling infrastructure code.
- [ ] Package request IDs, Problem Details, rate limiting, auditing, Kafka reliability, security defaults, and test fixtures as optional internal starters.
- [ ] Add a third minimal reference service only if it proves a missing pattern such as synchronous resilience, saga compensation, or gRPC.
- [ ] Add Renovate or Dependabot with grouped, verified platform upgrades.
- [ ] Add a documented compatibility matrix and automated upgrade tests for supported Java, Node, browser, database, Kafka, and Kubernetes versions.

## Product Direction (added 2026-10-05)

The product is the template itself; the user and order domain stays the smallest example that proves each pattern, as the rules above require. Generators such as JHipster or Spring Initializr produce a project once and leave it; they give no evidence that the result passes a security review, and generated projects drift from the template as soon as it improves. The niche to own is **the audited service starter for regulated enterprise teams**: Java and Angular teams in banking, insurance, energy and the public sector who must pass security, architecture and supplier reviews (DORA, NIS2, ISO/IEC 27001) before anything reaches production.

Differentiators:

1. A service generated with only the capabilities it needs, which keeps receiving template improvements as reviewable merge requests.
2. Compliance evidence shipped with every release: OWASP ASVS mapping, threat model, SBOM, signatures and scan results in one pack.
3. Every distributed-systems pattern demonstrated by its failure mode, with tests, runbooks and a removal path.
4. A measured production-readiness score for any project generated from the template.

### Generator And Upgrade Stream

- [ ] **ADR — distribution:** compare a templating generator with a stored answers file and three-way regeneration, a Maven archetype with an Angular schematic, and published starters only. The choice must let adopters receive later template changes; a one-shot copy does not. Builds on the initialization-script and starter items above.
- [ ] Capability selection at generation (Kafka with outbox, Redis cache, Keycloak, generated API clients, web UI): unselected patterns are absent from the generated code rather than disabled by configuration.
- [ ] Versioned template releases with semantic versions, a changelog, an upgrade guide per major version and a long-term-support line.
- [ ] Template update merge requests: a scheduled job in each generated project compares its template version with the latest release and opens a merge request with the changes, migration notes and marked conflicts.
- [ ] Conformance check runnable in any generated project's CI: reports where the project departs from the template's security, observability and testing guarantees, as a score with explanations.

### Compliance Evidence

- [ ] OWASP ASVS level 2 mapping: each requirement points to the implementing code or configuration and to the test that proves it; generated projects inherit the mapping, and CI reports met, missing and not-applicable requirements.
- [ ] Threat model (STRIDE) of the reference flow with a data-flow diagram, regenerated for the capabilities a project selects.
- [ ] Release evidence pack: SBOM, image signatures, test and coverage reports, Sonar gate, dependency and container scan results, the ASVS report and the threat model in one signed archive attached to each release (builds on the SBOM and signing item above).
- [ ] Secure development lifecycle template mapped to ISO/IEC 27001:2022 controls 8.25–8.29: review rules, branch protection, release approval and vulnerability-fix deadlines, with the CI checks that enforce them.
- [ ] Production-readiness review generated from automated checks (probes, resources, SLOs, alerts, runbooks, backups, disruption budgets) with a pass or fail report in CI.

### Enterprise Patterns (minimal domain, tested failure modes)

- [ ] Multi-tenancy pattern: tenant resolution from a token claim, PostgreSQL row-level security compared with schema per tenant, tenant-aware cache keys and Kafka headers, and tests proving cross-tenant access is denied.
- [ ] Idempotency keys for creating requests (`Idempotency-Key` header with stored responses), tested with concurrent retries.
- [ ] Zero-downtime schema changes (expand and contract) proven by a test that runs old and new versions against the same database during a rolling deployment.
- [ ] Relationship-based authorization with a policy engine beside role checks, added only with an example that roles cannot express.
- [ ] Internationalization pattern for the Angular UI and Problem Details messages (French and English).
- [ ] Generic Kubernetes target: generated projects deploy to any conformant cluster without the reference platform's onboarding contract, which stays an optional profile.

### Adoption And Offer

- [ ] **ADR — licensing:** the template is GPL 3.0 (`LICENSE` added 2026-10-05; `pom.xml` still declares no license). Code copied from a GPL template into proprietary services delivered to clients would have to be released under the GPL, which blocks most enterprise adoption. Choose a permissive license for the template and generated code, or a generator exception, then update `LICENSE`, the README and the build metadata.
- [ ] Versioned documentation site with a pattern catalog: for each pattern, the problem, the failure mode it handles, the code, the tests, the runbook and how to remove it.
- [ ] Guided failure-mode demos built on the fault-injection tests above (broker loss, duplicate events, database restart, identity-provider outage), with the expected Grafana and log views, usable in workshops.
- [ ] Measure and publish the time from generation to a first verified production deployment.
- [ ] Commercial offer around the free template: support subscription, architecture reviews of generated services and training based on the guided demos.
