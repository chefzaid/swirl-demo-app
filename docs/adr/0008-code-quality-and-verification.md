# ADR 0008: Keep Verification Layered And Continuous

- Status: Accepted
- Date: 2026-08-28

## Context

The template demonstrates behavior at multiple risk boundaries: validation/errors, JPA transactions, caching, OAuth security filters, Kafka retries/state, Angular authentication/API calls, containers, Kubernetes, identity, and GitOps rollout.

No single test type can cover these efficiently. Running only full-stack tests would be slow and hard to diagnose; running only mocked unit tests would miss production wiring.

## Decision

Use a test pyramid with explicit environment verification:

- unit tests for services, listeners, filters, cache, health, guards, interceptors, and API clients
- MVC tests for HTTP validation/error/DTO contracts
- full Spring context tests for JWT filter chains
- JaCoCo coverage evidence for deployable service logic
- Vitest component/service coverage
- Playwright local smoke and live authenticated critical flow in three engines
- containerized full-stack acceptance
- production Angular and packaged Java builds
- Kubernetes manifest rendering
- required GitLab compilation and image validation, with test/coverage and quality reports kept non-blocking
- internal smoke and exact-revision browser acceptance after rollout

Start changes at the narrowest useful layer and add integration coverage when behavior crosses infrastructure boundaries.

## Rationale

Fast tests give contributors tight feedback. Full-context tests catch configuration mistakes. Browser and post-rollout tests prove trust/routing/event boundaries that mocks cannot.

Packaging already-tested artifacts avoids divergence between what passed and what was deployed.

Failure/replay paths are first-class in an infrastructure template, so tests cover malformed input, unauthorized access, duplicate events, invalid transitions, missing data, and transient failures.

## Consequences

CI consumes more time/resources than a minimal build and browser acceptance depends on platform availability.

H2 remains insufficient for production persistence guarantees; PostgreSQL/Flyway and infrastructure tests must continue to grow.

The later delivery decision standardized an 80 percent reported coverage policy, since raised to 85 percent per layer (JaCoCo bundle check and Vitest thresholds), and keeps it non-blocking for release; coverage remains evidence rather than proof of adequate risk coverage.

Testcontainers, contracts, mutation, load, chaos, accessibility, supply-chain, and manifest policy tests remain roadmap work.

New technical patterns must include configuration, failure, operations, and tests—not only a happy-path example.

## Amendment: source analysis and discovery (2026-09-08)

Use one Sonar project per application repository to analyze its backend,
frontend and shared source together. Keep source paths, compiled inputs,
coverage reports and the scanner job in the application repository so template
adopters can adapt them to their stack.

The shared platform discovers application repositories through Argo CD tracking
of workloads in `apps`, provisions missing Sonar projects and analysis
credentials, and requests a first scan when analysis is absent. It also requests
refreshes when analysis is older than 24 hours, subject to the controller's
concurrency and retry limits. Discovery runs every 15 minutes. The application
declares its analysis inputs in `sonar-project.properties` and its scan-only CI
contract in `.sonar-auto.json`.

Keep manual scans available through a default-branch pipeline with
`SONAR_SCAN_ONLY=true`. Normal default-branch pipelines also run quality
automatically. Both manual and discovery-triggered scan-only pipelines run
build, test and quality without publishing images, releasing, deploying or
committing version changes. Completed analyses from any of these paths satisfy
the discovery freshness check.

This division keeps onboarding automatic without a central list of application
names, while preserving the build knowledge that only the application owns.
Periodic refreshes keep deployed repositories covered during periods without
commits; manual scans let contributors request feedback when needed. Missing
repository mappings or analysis contracts produce visible discovery errors and
must be corrected by the application owner.

Analysis remains non-blocking for release. Submission failures are visible in
the quality job, and a successful upload must still be processed by Sonar before
its findings and quality gate are available. The current Community Build setup
submits analysis for the default branch only.

See the [code-quality guide](../code-quality.md) for manual scan instructions,
template adaptation and troubleshooting, and
[ADR 0009](0009-explicit-delivery-jobs.md) for the delivery job rules.
