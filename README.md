# Swirl Demo App

Swirl Demo App is a deliberately small, production-shaped full-stack template. Two Java 25 / Spring Boot 4 services and an Angular 22 SPA demonstrate reusable application, event-driven, security, observability, testing, and GitOps patterns on the platform supplied by [`swirl-cloud`](https://github.com/chefzaid/swirl-cloud).

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Angular](https://img.shields.io/badge/Angular-22.1-red.svg)](https://angular.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-6.0-blue.svg)](https://www.typescriptlang.org/)

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
[observability scope](https://github.com/chefzaid/swirl-cloud/blob/main/docs/operations.md#namespace-and-discovery). When
copying this template, follow the [code-quality onboarding guide](./docs/code-quality.md#adapting-the-template)
to preserve source coverage, credentials and scan-only CI behavior.

[`VERSION`](./VERSION) owns the version baseline. See the
[version lifecycle](docs/deployment.md#version-lifecycle) for release numbering
and deliberate major-version changes.

## Documentation

- [Features](./docs/features.md)
- [Architecture Overview and ADR Index](./docs/architecture.md)
- [Data Model Reference](./docs/data-model.md)
- [Development Guide](./docs/development.md)
- [Testing Guide](./docs/testing.md)
- [Code Quality And Template Onboarding](./docs/code-quality.md)
- [Deployment Guide](./docs/deployment.md)
- [Operations Runbook](./docs/operations.md)
- [Security Reference](./docs/security.md)
- [Infrastructure Layout](./docs/deployment.md#infrastructure-layout)

## Roadmap

Future reusable technical capabilities are tracked in [TODO.md](./TODO.md). Product behavior stays intentionally minimal so every example remains easy to understand and transplant.

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

GPL 3.0. See [LICENSE](./LICENSE).
