# Technical Roadmap

Swirl Demo App is a reusable application template, not a product roadmap. New work should demonstrate a technical capability that can be transplanted into another service with little domain knowledge. User and order behavior should remain the smallest useful example needed to prove the pattern.

## Contribution Rules For Roadmap Work

- Prefer infrastructure, architecture, security, operability, testing, and developer-experience capabilities over business features.
- Add product behavior only when a technical pattern needs an end-to-end example.
- Keep optional infrastructure behind profiles, properties, or overlays so the default developer loop stays lightweight.
- Every added pattern needs tests, configuration documentation, operational notes, and a clear removal path.
- Do not add distributed-systems patterns merely to name them. Add them when the example can demonstrate the failure mode they solve.

## Bugs to fix

Bugs, failing checks, security findings, and code smells found while working on another task. Each entry names the file or area, what is wrong, and how to reproduce it.

- [x] Kafka consumers in both services use `JacksonJsonDeserializer` directly (`spring.kafka.consumer.value-deserializer` in each `application.yml`), so a record that cannot be deserialized (malformed JSON, or an `OrderStatus` value the consumer does not know) fails inside `poll()` before the listener's error handler sees it. The consumer stays on that offset and every later poll fails the same way, so the partition stops and the record never reaches the dead-letter topic (read from Spring Kafka 4.1.1's container code; not reproduced against a broker). Reproduce: publish `{"orderId":1,"status":"CANCELLED"}` to `order_topic`; the user service logs the deserialization error repeatedly and later records on that partition are not consumed. Fix with `ErrorHandlingDeserializer` delegating to the JSON deserializer and a dead-letter template that can write the raw bytes. Fixed: both services wrap the JSON deserializer in `ErrorHandlingDeserializer`, and `KafkaReliabilityConfig` dead-letters the original bytes through a byte-array template.
- [x] `swirlapp-web` coverage counts only files that some spec imports, so an untested file is invisible rather than reported at 0% (`NotificationComponent` showed up only once `AppComponent` gained a spec). Reproduce: `npm run test:coverage` lists no row for `src/main.ts` or any file no spec loads. Fixed: `angular.json` now includes `src/**/*.ts` and excludes only the bootstrap glue (`src/main.ts`, `src/environments/`).
- [x] The optional `01-e2e` job could not launch any browser: `package-lock.json` locks `@playwright/test` 1.64.0, which needs Chromium 1248, Firefox 1555 and WebKit 2370, but the job's pinned `mcr.microsoft.com/playwright` image (and the Compose `test-runner`'s `v1.62.1-noble`) ships the 1.62 builds. Reproduce: in the old image, `node -e "console.log(require('playwright-core').chromium.executablePath())"` from `swirlapp-web` names `/ms-playwright/chromium-1248/...`, which does not exist. Fixed: both runners use the `v1.64.0-noble` image, and `check-playwright-images.py` fails the build job when a runner image and the lockfile disagree.
- [x] `e2e/accessibility.spec.ts` failed on WebKit for the user and order screens once the E2E image could launch browsers: axe measured the active navigation link while its 0.2 s color fade was still running (contrast 1.15 to 3.02 instead of the settled ink-on-mint), and headless WebKit can leave such a transition unadvanced. Reproduce: in the `v1.64.0-noble` image, `npx playwright test e2e/accessibility.spec.ts --project=webkit --retries=0 --repeat-each=5`. Fixed: the check turns transitions off before axe measures, so it reads the colors users see once the fade ends.
- [x] `.github/workflows/sync-gitlab.yml` stopped at the first branch whose GitHub and GitLab copies conflict, and branches are processed alphabetically, so `main` never reached GitLab (and nothing deployed) after 2026-10-14 13:45 UTC: first the force-pushed agent branch `claude/apply-coding-md-vc7yw3` conflicted, then the Dependabot branch `dependabot/maven/maven-490fceb414` (`pom.xml`). Reproduce: any **Sync GitHub and GitLab** run since then ends with `Automatic merge failed` before reaching `main`. Fixed: a conflicting branch is reported and skipped while the rest still sync, and the run still fails at the final ref comparison; the agent branch now carries the GitLab copy as a no-change merge.
- [x] `.github/workflows/sync-gitlab.yml` failed every run after 2026-10-14: Dependabot force-pushes its rebases on GitHub while the sync never force-pushes GitLab, so `dependabot/maven/maven-490fceb414` and both `vitest` branches kept conflicting and their GitLab pipelines tested stale versions; it also re-created on GitHub the branch of the closed pull request #8 from its GitLab copy. Reproduce: any **Sync GitHub and GitLab** run since then ends with `GitHub and GitLab refs differ after reconciliation`. Fixed (owner's choice, 2026-10-15): `dependabot/*` branches are GitHub-owned; the sync replaces or deletes the GitLab copy with a lease on the fetched commit and never merges them.
- [x] Order events lost the request's trace once they went through the outbox: `OutboxRelay` publishes from a scheduled poll, so each record carried the poll's own trace and the `traceId` of the request no longer led through Kafka as `docs/operations.md` describes. Reproduce: create an order on `int` and compare the `traceId` of its `order.created` audit line with the `traceparent` header of its `order_topic` record. Fixed: each outbox row stores the change's `traceparent` and the relay sends the record inside that trace.
- [x] The dev container runs an older Keycloak than the Compose stack: `.devcontainer/docker-compose.yml` pins `quay.io/keycloak/keycloak:26.7.0` while `infra/compose/compose.yaml` pins `26.7.1`, although Dependabot's `docker-compose` entry watches both directories. Reproduce: `grep -rn "keycloak/keycloak:" .devcontainer infra/compose`. Fixed: the dev container pins 26.7.1, and the build job's `check-compose-images.py` fails when an image both files use is pinned differently.

## Needs Input

Parts of tasks that cannot advance without the owner's decision or information. Only the part needing input is set aside here; the rest of the task keeps moving. Answer under the item, and the next agent run applies the answer and removes the entry.

- [ ] Which private channel should `SECURITY.md` give for vulnerability reports, and which releases does it support? Channel: (a) GitHub private vulnerability reporting on the public mirror, which needs enabling in the repository's settings; (b) a security e-mail address you name; (c) GitLab confidential issues on `gitlab.swirlit.dev`. Releases: (1) only the latest minor release; (2) the latest two. The rest of the policy can be written without this. *(task: Product Direction › Identity And Positioning › SECURITY.md; asked by: Coding Agent)*

- [ ] Where does the Argo CD `ComparisonError: Object 'Kind' is missing` come from now? Every pinned `int`/`uat`/`prod` revision has a `kustomization.yaml`, so Argo CD should build with Kustomize and never parse `settings.json`, and all three environments render cleanly with `kustomize build` (every object has a `kind`). Please paste the current Application condition message (`kubectl -n infra get application swirl-demo-app-int -o jsonpath='{.status.conditions}'`) or say whether the platform forces the directory source type. *(task: Kubernetes And Delivery › Argo CD ComparisonError; asked by: Coding Agent)*
  - Answer:
- [ ] Every environment now ships its SLO rules, scoped to its own namespace, as the `swirl-demo-app-prometheus-rules` ConfigMap labelled `prometheus_rule: "1"`, but swirl-cloud's Prometheus (`k8s/platform/monitoring.yaml`) loads only its own `/etc/prometheus/rules.yaml`, so nothing reads them yet. Is `prometheus_rule: "1"` the label you want (it mirrors Grafana's `grafana_dashboard: "1"`)? The platform side is a `k8s-sidecar` container in the Prometheus pod like Grafana's dashboard sidecar (`LABEL=prometheus_rule`, `NAMESPACE=ALL`, `UNIQUE_FILENAMES=true`), writing into a directory added to `rule_files`, with `REQ_URL=http://localhost:9090/-/reload` and `REQ_METHOD=POST` (Prometheus rejects a GET reload), and a ClusterRole letting the Prometheus ServiceAccount list and watch ConfigMaps (like `grafana-dashboard-reader`). Remote clusters stay without rule evaluation until remote telemetry exists. *(task: Observability › Load SLO rules; asked by: Coding Agent)*
  - Answer:
- [ ] Which OTLP endpoint should deployed environments send traces to, and at what sampling rate? The services propagate trace context and log `traceId` already; setting `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` (for example `http://otel-collector.observability:4318/v1/traces`) turns span export on. If the platform has no collector yet, say so and the item stays parked. *(task: Observability › Export spans; asked by: Coding Agent)*

- [ ] Should an environment use the canary overlay (`infra/overlays/canary`)? It needs the Argo Rollouts controller and CRDs, which the platform does not install. Options: (a) keep it as an example only; (b) install Argo Rollouts in `swirl-cloud` and add a `canary` setting to `settings.json` so `render-deployment.py` can select the overlay per environment, starting with `int`. *(task: Kubernetes And Delivery › Canary deployment; asked by: Infra Agent)*
  - Answer:

## Priority: Delivery And Data Correctness

- [x] Replace the order database/Kafka dual write with a transactional outbox and a small relay implementation; provide polling and CDC variants as documented alternatives.
- [x] Add an inbox or processed-event store so consumer idempotency survives restarts and duplicate events can be measured.
- [x] Add DLT inspection, replay, quarantine, and retention tooling with an operator-safe runbook.
- [x] Define event schema compatibility rules and demonstrate versioned events with backward-compatible consumers.
- [ ] Add Testcontainers integration tests for PostgreSQL, Redis, and Kafka, including migration, retry, DLT, duplicate-delivery, and restart scenarios.
- [ ] Demonstrate saga orchestration or choreography only after adding a second compensating write; keep the example domain minimal.

## Priority: Security And API Governance

- [x] Add reusable role/authority mapping from Keycloak claims and method-level authorization examples with `@PreAuthorize`.
- [x] Add an authorization test matrix covering anonymous, authenticated, wrong-role, and permitted callers.
- [x] Move OpenAPI access behind a production switch or operator authorization while leaving it convenient in development.
- [ ] Turn public API documentation off in `uat` and `prod` (`"apiDocs": false` in their `settings.json`) once a release containing the `apiDocs` switch runs there; older web images reject the extra runtime-config key, so the switch stays on until then.
- [ ] Add a distributed rate-limit implementation at the gateway or Redis layer; retain the in-process filter as the single-instance reference.
- [ ] Add API versioning and compatibility policy, generated frontend clients, and contract-drift checks in CI.
- [x] Add an optional HATEOAS affordance example and explain when Richardson level 3 is worth the client coupling; keep OpenAPI documentation distinct from hypermedia controls.
- [ ] Add consumer-driven contract tests for service and event contracts.
- [ ] Add a small field-encryption example backed by envelope encryption/KMS abstractions; document key rotation and keep passwords in Keycloak.
- [ ] Add SBOM generation, image signing and verification, secret scanning, SAST, dependency scanning, and a baseline DAST stage. Done so far: `03-security` scans dependencies, IaC and secrets with Trivy and publishes a CycloneDX source SBOM. Remaining: image SBOMs and signatures at release with verification before deployment, SAST, and baseline DAST.
- [x] Resolve the findings of the CI `03-security` job (2026-10-04, pipeline 723): one critical prototype-pollution issue in a build-time worker-pool dependency and high denial-of-service issues in the router and HTTP-client dependencies; the job is report-only, so they do not block delivery. Fixed 2026-10-14 by moving Angular to 22.2.2 with a refreshed lockfile (piscina, router, undici, fast-uri, proxy-addr) and pinning Jackson 2.21.7/3.1.7 and lz4-java 1.11.4; a local `trivy fs` reports no HIGH or CRITICAL findings.
- [x] Give the order, user and web deployments an explicit non-root pod and container `securityContext` (Trivy KSV-0118 reports the default context, which allows root). The base Deployments already set it; the finding came from the HA overlay's standalone partial Deployments, now inline patches in `infra/overlays/ha/kustomization.yaml` with an identical rendered output.
- [ ] Define retention, export, anonymization, and deletion examples for personal data without expanding the demo into an account-management product.
- [x] Add request-size limits: the services answer bodies over 64 KB with `413` (chunked bodies once they pass the limit) and chunked form or multipart bodies with `411`, behind the ingress's 10 MB limit.
- [x] Add edge security examples for global throttling and optional bot protection (Traefik `rateLimit` middleware chained after `swirl-demo-app-upload-limit`, opt-in per environment setting, since the client IP Traefik sees depends on the platform's load balancer). The `edgeRateLimit` setting chains `swirl-demo-app-rate-limit` ahead of the upload limit, not after it, so a throttled request is refused before Traefik buffers its body, and ahead of the database-reading health routes; burst is capped at twice the average because Traefik restarts an idle visitor's bucket full, and remote environments are refused because their Traefik trusts no forwarding proxy. Bot protection is documented as a Cloudflare zone setting, since the platform forbids plugin and `forwardAuth` Middlewares.

## Priority: Resilience And Asynchronous Work

- [ ] Add a deliberately small outbound HTTP adapter so timeout, retry with jitter, circuit breaker, bulkhead, and fallback behavior can be demonstrated and tested realistically.
- [ ] Provide a bounded `@Async` executor example with context propagation, backpressure, shutdown behavior, and a job-status resource; do not use unbounded fire-and-forget work.
- [x] Add a scheduled maintenance example for records with explicit retention semantics, leader election, idempotency, and dry-run support.
- [x] Define cache behavior for Redis outages, stampede prevention, eviction/versioning, and stale-data tradeoffs.
- [x] Add graceful Kafka degradation and readiness semantics that distinguish optional messaging from a required dependency.
- [ ] Add a dynamic feature-flag SPI with local configuration and an optional OpenFeature/FF4J provider, safe defaults, targeting tests, change auditing, and a clear distinction from user entitlements.
- [ ] Add fault-injection and recovery tests for database, Kafka, Redis, and identity-provider outages.

## Priority: Observability And Operations

- [x] Add OpenTelemetry traces and propagate trace context across HTTP and Kafka alongside the existing request ID.
- [ ] Export spans from deployed environments to the platform's trace collector *(waiting in Needs Input)*.
- [x] Add reusable Micrometer business/event metrics for publish latency, consumer lag, retries, DLT records, cache behavior, and rate-limit rejections.
- [x] Define service-level indicators, objectives, Prometheus alerts, and Grafana alert views for availability, latency, errors, saturation, and event backlog.
- [x] Add log redaction tests and a documented policy for tokens, personal data, request bodies, and exception details.
- [ ] Load `infra/observability/slo-rules.yaml` into each environment's Prometheus *(waiting in Needs Input)*. Done so far: each environment ships a copy scoped to its namespace as a ConfigMap labelled `prometheus_rule: "1"`, and the alerts use the platform's `critical`/`warning` severities. Remaining: the platform Prometheus loading that label.
- [ ] Add optional log/metric anomaly detection only after stable baselines and actionable alert ownership are defined.
- [x] Add audit events for security-sensitive actions and document separation between entity auditing, application audit trails, and platform logs.
- [ ] Add backup/restore drills for PostgreSQL and persistent Kafka/Redis data, with recovery-point and recovery-time targets.
- [ ] Add load, soak, and capacity baselines with reproducible k6 or Gatling scenarios.

## Priority: Kubernetes And Delivery

- [ ] Add environment-specific Kustomize overlays or a Helm chart for names, domains, registry, replicas, and resource sizing.
- [ ] Add an optional API-gateway profile for centralized routing, authentication policy, quotas, contract publication, and edge observability without replacing service-side controls.
- [x] Add HorizontalPodAutoscaler, PodDisruptionBudget, topology spread, and rolling/canary deployment examples. The HA overlay carries the autoscalers, disruption budgets, topology spread and rolling-update settings; canary delivery is split out below.
- [x] Add a canary deployment example (for example Argo Rollouts with an analysis step on error rate), kept optional like the HA overlay. `infra/overlays/canary` wraps the Deployments in Rollouts with a Prometheus error-rate analysis; it is rendered and policy-checked in CI, and enabling it waits in Needs Input.
- [x] Add explicit egress NetworkPolicies for PostgreSQL, Redis, Kafka, Keycloak, DNS, and approved external endpoints. `allow-swirl-demo-app-egress` ships with the base and prod (dedicated `int`/`uat` namespaces keep the platform's policies), and the manifest policy check rejects a target whose workloads lack an egress policy. Prod picks it up with its next release.
- [ ] Add optional service-mesh documentation only when mTLS, traffic policy, or service-to-service telemetry is demonstrated.
- [ ] Add ephemeral preview environments and automated teardown for pull requests.
- [x] Add policy-as-code checks for Kubernetes manifests and container security contexts.
- [ ] Add release promotion, rollback, provenance, and environment-approval examples without weakening Argo CD ownership.
- [ ] *(waiting in Needs Input)* Fix the `swirl-demo-app-int`/`-uat`/`-prod` Argo CD `ComparisonError: Object 'Kind' is missing`: the Application source directory also contains `infra/environments/<env>/settings.json`, which Argo CD parses as a manifest. Exclude it (`directory.exclude`) or move the settings outside the source path.

## Priority: Testing And Quality

- [x] Add ArchUnit rules for shared-module and service boundaries.
- [x] Add mutation testing for service and event-state logic.
- [x] Add REST-assured or full-context HTTP integration tests against packaged applications.
- [x] Add accessibility testing for the Angular critical path and automated checks for keyboard navigation and semantic labels.
- [x] Add visual regression tests for the deliberately small UI surface. `e2e/visual.spec.ts` compares the login, user, and order screens in Chromium, Firefox, and WebKit against baselines made in the pinned Playwright image, and the optional E2E job runs it.
- [ ] Extend the existing automatic Sonar reporting with Java 25-specific analyzer validation, OWASP Dependency-Check, and opt-in enforcement of quality gates.
- [x] Add Markdown linting and internal-link validation for `README.md`, `TODO.md`, and `docs/`.

## Priority: Template Experience

- [ ] Make the dev container application-only and point each development environment at shared infrastructure namespaces; retain local infrastructure as an explicit opt-in profile.
- [ ] Add a template initialization script for group ID, package, service names, image registry, domains, namespaces, and identity-provider settings.
- [ ] Separate example-domain modules from reusable starters so adopters can remove users/orders without untangling infrastructure code.
- [ ] Package request IDs, Problem Details, rate limiting, auditing, Kafka reliability, security defaults, and test fixtures as optional internal starters.
- [ ] Add a third minimal reference service only if it proves a missing pattern such as synchronous resilience, saga compensation, or gRPC.
- [x] Add Renovate or Dependabot with grouped, verified platform upgrades.
- [ ] Add a documented compatibility matrix and automated upgrade tests for supported Java, Node, browser, database, Kafka, and Kubernetes versions.

## Product Direction (added 2026-10-05)

The product is the template itself; the user and order domain stays the smallest example that proves each pattern, as the rules above require. Generators such as JHipster or Spring Initializr produce a project once and leave it; they give no evidence that the result passes a security review, and generated projects drift from the template as soon as it improves. The niche to own is **the audited service starter for regulated enterprise teams**: Java and Angular teams in banking, insurance, energy and the public sector who must pass security, architecture and supplier reviews (DORA, NIS2, ISO/IEC 27001) before anything reaches production. [ADR 0014](docs/adr/0014-template-scope-and-audience.md) records this scope, the audience and the non-goals.

Differentiators:

1. A service generated with only the capabilities it needs, which keeps receiving template improvements as reviewable merge requests.
2. Compliance evidence shipped with every release: OWASP ASVS mapping, threat model, SBOM, signatures and scan results in one pack.
3. Every distributed-systems pattern demonstrated by its failure mode, with tests, runbooks and a removal path.
4. A measured production-readiness score for any project generated from the template.

### Identity And Positioning

These items make what the template is (a reusable service starter whose user and order flow is only the smallest example), what it does, and what it aims at (the audited starter above) read the same wherever someone meets it, without presenting roadmap work as shipped.

- [x] **ADR — scope and audience:** add `docs/adr/0014-template-scope-and-audience.md` recording this Product Direction as the template's identity: the product is the template and the user/order flow only its smallest example; the audience is Java and Angular teams in regulated sectors facing security, architecture and supplier reviews; new work follows the Contribution Rules above; the non-goals are a growing business domain, one-shot generated copies, certifying anyone as compliant, and a mesh, saga or gateway before an example needs one; and nothing is called audited, evidenced or compliant before its evidence ships. List it in `docs/architecture.md`, link it from the Product Direction paragraph, and amend ADR 0002, whose "login and create/list flows only" no longer matches the SPA's edit and delete views.
- [x] Add a **Before You Adopt** section ahead of **Adapting The Template** in `docs/code-quality.md`, linked from `README.md` and `docs/development.md`, listing what a team accepts today, each with its source: GPL 3.0 for distributed services (ADR 0013), the `swirl-cloud` onboarding contract for cluster delivery (Compose runs anywhere), a user/order example that cannot yet be removed cleanly and manual renaming, a pinned stack without a support matrix, and no ASVS mapping, threat model or evidence pack yet. Every condition links to an ADR, a guide section or an open TODO item, and none is already solved.
- [x] Rewrite the top of `README.md` so that, before **GitLab Delivery**, a first-time reader learns what the template is, who it is for, what it does (a five-step summary of the order-validation flow linking `docs/features.md#minimal-demonstration-flow`) and what it aims at, with a status line separating what ships today from roadmap work and a short **What it is not** list taken from ADR 0014. Point **Roadmap** at Product Direction too, give `docs/features.md` the same audience sentence, and group **Documentation** by reader (evaluators, architects, contributors, deployers, operators, reviewers, as ADR 0001 describes), adding the unlinked `docs/dns.md`.
- [ ] Bring `docs/features.md` in line with done roadmap items it does not list yet (for example the ArchUnit rules, mutation testing, the manifest policy check, the HA and canary overlays, the Trivy scans with the source SBOM, the Markdown and link checks, and grouped Dependabot upgrades), fix passages that still call shipped behavior roadmap work (cache degradation, and "canary rollout or environment overlays" under **Current Technical Boundaries**), and drop the requests in `docs/architecture.md` › **Proposed ADRs For Roadmap Work** that are already decided (role mapping, event schema compatibility). Done when every completed Priority item has a features entry or a link to the guide that documents it, and every remaining boundary matches an open item.
- [x] Use one identity sentence wherever the repository describes itself, and check it in CI. The README's first sentence (plain text, at most 160 characters) also becomes the description in `pom.xml`, `swirlapp-web/package.json`, the meta description in `swirlapp-web/src/index.html`, the GitLab project description default in `infra/scripts/configure-code-quality.sh` (whose default topics also gain `template`), and the release description in `infra/scripts/ci-release.sh`. A new `infra/scripts/check-identity.py`, with its test, runs from `ci-verify.sh` and names the file that drifts; `docs/code-quality.md` lists these surfaces as the identity an adopter replaces.
- [x] Give both API documents and `/actuator/info` an identity and the running release. The Swagger UI shows springdoc's defaults (`OpenAPI definition`, `v0`), and both `application.yml` files hard-code `info.app.version: 1.0.0` while `VERSION` is 1.13.0. Take the version from the build, and give each service an OpenAPI title (`Swirl Demo App users API`, `Swirl Demo App orders API`), a short description of its step in the order-validation flow as the template's example service, the version, the GPL 3.0 license, and `externalDocs` pointing at `docs/features.md`. Tests assert the title, the Maven version and the license, and that `/actuator/info` reports the same version.
- [ ] Make the running SPA name the app consistently and claim only what it does. The brand shows `DEV/APP` / `cluster lab` while its accessible name is `Swirl Demo App home` (WCAG 2.5.3 Label in Name), every route shares one tab title, and four statements are not backed by the code: creating an order "warms Redis" (it evicts the cache), every order card says "Event pipeline connected" whatever its status, and the header's "Platform online" and the footer's "Live reference environment" are fixed text. Show `Swirl Demo App` in the brand (keeping "cluster lab" as a small descriptor), give each route its own title, add a sentence on the login page saying this is the reference flow of a service template with a link to its README, explain each order status instead of the fixed card line, and remove or reword the unchecked claims. Cover it with unit specs, `e2e/home.spec.ts`, the accessibility suite and regenerated visual baselines.
- [ ] Add a **Try The Flow** walkthrough to the README Quick Start: create a user, create an order for it and watch `PENDING` become `APPROVED`, then reach `REJECTED` without extra tooling (or say that it cannot be, rather than adding UI). Each step names where it shows up (the audit log line and its `traceId`, the Kafka topics, the Swagger UI) and links the pattern it demonstrates in `docs/features.md`. Extend `e2e/live-cluster.spec.ts` with the rejection path so the walkthrough stays true, and quote only statuses and log lines observed against the Compose stack.
- [ ] Add a `SECURITY.md` vulnerability-disclosure policy, which supplier reviews ask for: the supported releases, how to report privately, what reporters can expect and when, and links from `README.md` and `.gitlab/issue_templates/Security.md` *(reporting channel and supported releases waiting in Needs Input)*.

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

- [x] **ADR — licensing:** the owner kept GPL 3.0 unchanged for the template and generated code ([ADR 0013](docs/adr/0013-gpl-license.md)); `pom.xml` now declares it.
- [ ] Versioned documentation site with a pattern catalog: for each pattern, the problem, the failure mode it handles, the code, the tests, the runbook and how to remove it.
- [ ] Guided failure-mode demos built on the fault-injection tests above (broker loss, duplicate events, database restart, identity-provider outage), with the expected Grafana and log views, usable in workshops.
- [ ] Measure and publish the time from generation to a first verified production deployment.
- [ ] Commercial offer around the free template: support subscription, architecture reviews of generated services and training based on the guided demos.
