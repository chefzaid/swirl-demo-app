# Swirl Demo App

Swirl Demo App is a production-shaped Java and Angular service template for teams whose services must pass security, architecture and supplier reviews.

Two Java 25 / Spring Boot 4 services and an Angular 22 SPA demonstrate reusable application, event-driven, security, observability, testing, and GitOps patterns on the platform supplied by [`swirl-cloud`](https://github.com/chefzaid/swirl-cloud). The product is the template itself: its user and order example is only the smallest flow that proves each pattern end to end ([ADR 0014](./docs/adr/0014-template-scope-and-audience.md)).

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Angular](https://img.shields.io/badge/Angular-22.2-red.svg)](https://angular.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-6.0-blue.svg)](https://www.typescriptlang.org/)

## What It Does

The example is one order-validation flow ([details](./docs/features.md#minimal-demonstration-flow)):

1. `swirlapp-user` stores a user created through its API or the SPA.
2. An order is created for that user; `swirlapp-order` stores it as `PENDING` and publishes a validation request through its transactional outbox.
3. `swirlapp-user` consumes the request, looks up the user and publishes an `APPROVED` or `REJECTED` result.
4. `swirlapp-order` checks the result and updates the order, ignoring duplicates and late results.
5. The Angular SPA lists both resources and can create, edit and delete each one.

On the way the flow exercises Keycloak JWT validation at each API, PostgreSQL with Flyway, Redis caching, Kafka retries and dead-letter topics, tracing and audit logs, and delivery from GitLab CI through Argo CD.

## Aim And Status

The template aims to become an audited service starter for regulated enterprise teams: a service generated with only the capabilities it needs, which keeps receiving template improvements, and which ships the evidence that security and supplier reviews ask for ([Product Direction](./TODO.md#product-direction-added-2026-10-05)).

**Status:** the services, SPA, tests and delivery pipeline in [Features](./docs/features.md) ship today. Capability selection at generation, template update merge requests, the compliance evidence pack and the readiness score are roadmap work. [Before You Adopt](./docs/code-quality.md#before-you-adopt) lists what a service built from the template accepts today.

### What It Is Not

- A business domain: the user and order example stays the smallest one that proves each pattern.
- A one-shot generator: the aim is services that keep receiving template changes, not copies that drift from it.
- A compliance certificate: the aim is to supply evidence for an organisation's own reviews, not to certify anyone, and until that evidence ships the template calls nothing audited, evidenced or compliant.
- A pattern catalogue for its own sake: a service mesh, a saga or an API gateway arrives only with an example of the failure mode it handles.

## GitLab Delivery

Use the platform's `add-repos.sh` entry point to install or reconfigure this
repository through its [declarative onboarding contract](docs/deployment.md#add-or-reconfigure-this-repository).
Choose the application's hostname label; onboarding supplies the shared platform
settings and each target's configuration. Java and Angular images run unchanged
across environments, with runtime configuration and scoped credentials supplied
by Kubernetes and Vault.

Every commit builds and deploys a snapshot to `int`. Publishing a release from a
default-branch commit pipeline deploys it to `uat`. `prod` is a manual pipeline that
promotes the release running on `uat`, without rebuilding. Central Argo CD deploys
only the selected environment's namespace. All three environments
can share the platform cluster; remote clusters are optional. See the
[delivery flow](docs/deployment.md#delivery-flow) for prerequisites and verification.

CI submits Sonar analysis for both the Java backend and Angular frontend. The
platform's scheduled discovery currently covers its local workloads; remote
application clusters need separate telemetry/discovery integration. See
[observability scope](https://github.com/chefzaid/swirl-cloud/blob/main/docs/operations.md#namespace-and-discovery). Before
copying this template, read [Before You Adopt](./docs/code-quality.md#before-you-adopt)
for what a service built from it accepts today. When copying it, follow the
[code-quality onboarding guide](./docs/code-quality.md#adapting-the-template)
to preserve source coverage, credentials and scan-only CI behavior.

[`VERSION`](./VERSION) owns the version baseline. See the
[version lifecycle](docs/deployment.md#version-lifecycle) for release numbering
and deliberate major-version changes.

## Documentation

Each reader has a starting point ([ADR 0001](./docs/adr/0001-documentation-structure.md)):

- **Evaluators:** [Features](./docs/features.md) and [Before You Adopt](./docs/code-quality.md#before-you-adopt)
- **Architects:** [Architecture Overview and ADR Index](./docs/architecture.md) and [Data Model Reference](./docs/data-model.md)
- **Contributors:** [Development Guide](./docs/development.md), [Testing Guide](./docs/testing.md) and [Code Quality And Template Onboarding](./docs/code-quality.md)
- **Deployers:** [Deployment Guide](./docs/deployment.md), [Infrastructure Layout](./docs/deployment.md#infrastructure-layout) and [Public DNS](./docs/dns.md)
- **Operators:** [Operations Runbook](./docs/operations.md) and [Service Levels And Alerts](./docs/slo.md)
- **Reviewers:** [Security Reference](./docs/security.md)

## Roadmap

Future reusable technical capabilities are tracked in [TODO.md](./TODO.md), and its [Product Direction](./TODO.md#product-direction-added-2026-10-05) sets where the template is heading. The user and order example's business behavior stays intentionally minimal so every example remains easy to understand and transplant.

## Quick Start

Docker builds and runs the complete authenticated demo, including PostgreSQL, Redis, Kafka, and Keycloak:

```bash
docker compose -f infra/compose/compose.yaml up --build -d
docker compose -f infra/compose/compose.yaml ps
```

Open <http://localhost:4200> and sign in with username `user` and password `password`. These credentials are public and intended only for the disposable demo environment.

Useful commands:

```bash
docker compose -f infra/compose/compose.yaml logs -f swirlapp-user swirlapp-order
docker compose -f infra/compose/compose.yaml down
mvn clean verify
cd swirlapp-web && npm ci && npm test
```

See the [Development Guide](./docs/development.md) for dependency-free local development and the [Testing Guide](./docs/testing.md) for the complete verification matrix.

## License

GPL 3.0, for the template and the code it generates. See [LICENSE](./LICENSE) and [ADR 0013](./docs/adr/0013-gpl-license.md) for what that means for services built from it.
